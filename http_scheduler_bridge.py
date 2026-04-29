#!/usr/bin/env python3

import json
import math
import threading
from http import HTTPStatus
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any, Dict, Optional, Tuple

import rclpy
from geometry_msgs.msg import PoseStamped, PoseWithCovarianceStamped
from rclpy.node import Node
from rclpy.qos import QoSProfile


def yaw_to_quaternion(yaw: float) -> Tuple[float, float, float, float]:
    half_yaw = yaw * 0.5
    return 0.0, 0.0, math.sin(half_yaw), math.cos(half_yaw)


class HttpSchedulerBridge(Node):
    def __init__(self) -> None:
        super().__init__("http_scheduler_bridge")

        self.http_address = self.declare_parameter("http_address", "0.0.0.0").value
        self.http_port = int(self.declare_parameter("http_port", 8080).value)
        self.goal_topic = self.declare_parameter("goal_topic", "/goal_pose").value
        self.pose_topic = self.declare_parameter("pose_topic", "/amcl_pose").value
        self.default_frame_id = self.declare_parameter("default_frame_id", "map").value

        qos = QoSProfile(depth=10)
        self.goal_pub = self.create_publisher(PoseStamped, self.goal_topic, qos)
        self.pose_sub = self.create_subscription(
            PoseWithCovarianceStamped,
            self.pose_topic,
            self._on_pose,
            qos,
        )

        self._lock = threading.Lock()
        self._latest_pose: Optional[PoseWithCovarianceStamped] = None
        self._latest_goal: Optional[PoseStamped] = None

        handler = self._build_handler()
        self._server = ThreadingHTTPServer((self.http_address, self.http_port), handler)
        self._server.daemon_threads = True
        self._server_thread = threading.Thread(
            target=self._server.serve_forever,
            name="http_scheduler_bridge_server",
            daemon=True,
        )
        self._server_thread.start()

        self.get_logger().info(
            f"HTTP scheduler bridge listening on {self.http_address}:{self.http_port}, "
            f"goal_topic={self.goal_topic}, pose_topic={self.pose_topic}"
        )

    def destroy_node(self) -> bool:
        self._server.shutdown()
        self._server.server_close()
        self._server_thread.join(timeout=2.0)
        return super().destroy_node()

    def _on_pose(self, msg: PoseWithCovarianceStamped) -> None:
        with self._lock:
            self._latest_pose = msg

    def _build_handler(self):
        bridge = self

        class RequestHandler(BaseHTTPRequestHandler):
            def do_GET(self) -> None:
                if self.path == "/healthz":
                    self._send_json(
                        HTTPStatus.OK,
                        {
                            "ok": True,
                            "transport": "http",
                            "goal_topic": bridge.goal_topic,
                            "pose_topic": bridge.pose_topic,
                        },
                    )
                    return

                if self.path == "/api/v1/scheduler/ros/pose":
                    pose = bridge._latest_pose_to_dict()
                    if pose is None:
                        self._send_json(
                            HTTPStatus.SERVICE_UNAVAILABLE,
                            {"ok": False, "message": f"No message received on {bridge.pose_topic} yet."},
                        )
                        return
                    self._send_json(HTTPStatus.OK, {"ok": True, "pose": pose})
                    return

                if self.path == "/api/v1/scheduler/ros/goal":
                    goal = bridge._latest_goal_to_dict()
                    if goal is None:
                        self._send_json(
                            HTTPStatus.NOT_FOUND,
                            {"ok": False, "message": "No goal has been published yet."},
                        )
                        return
                    self._send_json(HTTPStatus.OK, {"ok": True, "goal": goal})
                    return

                self._send_json(HTTPStatus.NOT_FOUND, {"ok": False, "message": "Not found"})

            def do_POST(self) -> None:
                if self.path != "/api/v1/scheduler/ros/goal":
                    self._send_json(HTTPStatus.NOT_FOUND, {"ok": False, "message": "Not found"})
                    return

                content_length = int(self.headers.get("Content-Length", "0"))
                raw_body = self.rfile.read(content_length) if content_length > 0 else b""

                try:
                    payload = json.loads(raw_body.decode("utf-8") or "{}")
                except json.JSONDecodeError as exc:
                    self._send_json(
                        HTTPStatus.BAD_REQUEST,
                        {"ok": False, "message": f"Invalid JSON: {exc.msg}"},
                    )
                    return

                try:
                    goal_msg = bridge._goal_from_payload(payload)
                except ValueError as exc:
                    self._send_json(HTTPStatus.BAD_REQUEST, {"ok": False, "message": str(exc)})
                    return

                bridge.goal_pub.publish(goal_msg)
                with bridge._lock:
                    bridge._latest_goal = goal_msg

                bridge.get_logger().info(
                    "Received HTTP goal: "
                    f"x={goal_msg.pose.position.x:.3f}, "
                    f"y={goal_msg.pose.position.y:.3f}, "
                    f"frame={goal_msg.header.frame_id}"
                )
                self._send_json(
                    HTTPStatus.OK,
                    {"ok": True, "goal": bridge._pose_stamped_to_dict(goal_msg)},
                )

            def do_OPTIONS(self) -> None:
                self.send_response(HTTPStatus.NO_CONTENT)
                self._send_common_headers()
                self.end_headers()

            def log_message(self, format: str, *args: Any) -> None:
                bridge.get_logger().debug(format % args)

            def _send_json(self, status: HTTPStatus, payload: Dict[str, Any]) -> None:
                body = json.dumps(payload, ensure_ascii=True).encode("utf-8")
                self.send_response(status)
                self._send_common_headers()
                self.send_header("Content-Type", "application/json; charset=utf-8")
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def _send_common_headers(self) -> None:
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
                self.send_header("Access-Control-Allow-Headers", "Content-Type")

        return RequestHandler

    def _goal_from_payload(self, payload: Dict[str, Any]) -> PoseStamped:
        if not isinstance(payload, dict):
            raise ValueError("JSON body must be an object.")

        header = payload.get("header") if isinstance(payload.get("header"), dict) else {}
        pose = payload.get("pose") if isinstance(payload.get("pose"), dict) else payload
        position = pose.get("position") if isinstance(pose.get("position"), dict) else pose
        orientation = pose.get("orientation") if isinstance(pose.get("orientation"), dict) else None

        x = self._read_float(position, ("x",))
        y = self._read_float(position, ("y",))
        z = self._read_float(position, ("z",), default=0.0)

        if orientation:
            qx = self._read_float(orientation, ("x",), default=0.0)
            qy = self._read_float(orientation, ("y",), default=0.0)
            qz = self._read_float(orientation, ("z",), default=0.0)
            qw = self._read_float(orientation, ("w",), default=1.0)
        else:
            yaw = self._read_float(pose, ("yaw", "theta", "angle"), default=0.0)
            qx, qy, qz, qw = yaw_to_quaternion(yaw)

        frame_id = str(header.get("frame_id") or pose.get("frame_id") or self.default_frame_id)

        goal_msg = PoseStamped()
        goal_msg.header.stamp = self.get_clock().now().to_msg()
        goal_msg.header.frame_id = frame_id
        goal_msg.pose.position.x = x
        goal_msg.pose.position.y = y
        goal_msg.pose.position.z = z
        goal_msg.pose.orientation.x = qx
        goal_msg.pose.orientation.y = qy
        goal_msg.pose.orientation.z = qz
        goal_msg.pose.orientation.w = qw
        return goal_msg

    @staticmethod
    def _read_float(data: Dict[str, Any], keys: Tuple[str, ...], default: Optional[float] = None) -> float:
        for key in keys:
            if key in data:
                return float(data[key])
        if default is not None:
            return default
        joined_keys = ", ".join(keys)
        raise ValueError(f"Missing numeric field: {joined_keys}")

    def _latest_pose_to_dict(self) -> Optional[Dict[str, Any]]:
        with self._lock:
            pose = self._latest_pose
        if pose is None:
            return None

        return {
            "frame_id": pose.header.frame_id,
            "stamp": {
                "sec": int(pose.header.stamp.sec),
                "nanosec": int(pose.header.stamp.nanosec),
            },
            "position": {
                "x": pose.pose.pose.position.x,
                "y": pose.pose.pose.position.y,
                "z": pose.pose.pose.position.z,
            },
            "orientation": {
                "x": pose.pose.pose.orientation.x,
                "y": pose.pose.pose.orientation.y,
                "z": pose.pose.pose.orientation.z,
                "w": pose.pose.pose.orientation.w,
            },
            "covariance": list(pose.pose.covariance),
        }

    def _latest_goal_to_dict(self) -> Optional[Dict[str, Any]]:
        with self._lock:
            goal = self._latest_goal
        if goal is None:
            return None
        return self._pose_stamped_to_dict(goal)

    @staticmethod
    def _pose_stamped_to_dict(goal: PoseStamped) -> Dict[str, Any]:
        return {
            "frame_id": goal.header.frame_id,
            "stamp": {
                "sec": int(goal.header.stamp.sec),
                "nanosec": int(goal.header.stamp.nanosec),
            },
            "position": {
                "x": goal.pose.position.x,
                "y": goal.pose.position.y,
                "z": goal.pose.position.z,
            },
            "orientation": {
                "x": goal.pose.orientation.x,
                "y": goal.pose.orientation.y,
                "z": goal.pose.orientation.z,
                "w": goal.pose.orientation.w,
            },
        }


def main() -> None:
    rclpy.init()
    node = HttpSchedulerBridge()
    try:
        rclpy.spin(node)
    finally:
        node.destroy_node()
        rclpy.shutdown()


if __name__ == "__main__":
    main()
