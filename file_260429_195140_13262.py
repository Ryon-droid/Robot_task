# -*- coding: utf-8 -*-
import asyncio
import websockets
import json
import numpy as np
import jkrc
import time

# ===================== 配置区 =====================
HOST = "0.0.0.0"
PORT = 8765
ROBOT_IP = "10.5.5.100"

# 相机内参
camera_param = np.load("camera_param.npz")
K = camera_param["mtx"]

# 机器人官方常量
COORD_JOINT = 1
INCR = 1
ABS  = 0

# 机器人实例
robot = None

# ===================== 像素 → 相机坐标（公式正确） =====================
def pixel2camera_pose(u, v, z, K):
    fx = K[0,0]
    fy = K[1,1]
    cx = K[0,2]
    cy = K[1,2]
    x = (u - cx) * z / fx
    y = (v - cy) * z / fy
    return round(x, 3), round(y, 3), round(z, 3)

# ===================== 正运动学 =====================
def get_current_joint_and_pose():
    ret, status = robot.get_robot_status()
    current_joint = [j[0] for j in status[20][5]]
    ret, pose = robot.kine_forward(current_joint)
    return current_joint, pose

# ===================== 相机系 → 基坐标系 =====================
def object_to_base(xc, yc, zc, current_pose):
    tx = current_pose[0] + xc * 1000
    ty = current_pose[1] + yc * 1000
    tz = current_pose[2] + zc * 1000
    rx = current_pose[3]
    ry = current_pose[4]
    rz = current_pose[5]
    return [tx, ty, tz, rx, ry, rz]

# ===================== 逆运动学 =====================
def solve_ik(current_joint, target_pose):
    res = robot.kine_inverse(current_joint, target_pose)
    return res

# ===================== 初始化机器人 =====================
def init_robot():
    global robot
    robot = jkrc.RC(ROBOT_IP)
    robot.login()
    time.sleep(0.5)
    robot.drag_mode_enable(False)
    time.sleep(1)
    robot.power_on()
    time.sleep(3)
    robot.enable_robot()
    time.sleep(1)
    print("✅ 机器人已就绪")

# ===================== WebSocket 自动处理 =====================
async def handle_client(websocket):
    print("✅ 视觉客户端已连接（自动接收、自动计算、自动回传）")

    while True:
        try:
            # 1. 接收视觉数据（按你协议）
            recv_data = await websocket.recv()
            data = json.loads(recv_data)

            obj_name = data.get("obj_name")
            cx = data.get("cx", 0)
            cy = data.get("cy", 0)
            z = data.get("z", 0.0)

            print(f"\n📥 接收目标：{obj_name} | cx={cx}, cy={cy}, z={z:.3f}")

            # 2. 像素 → 相机坐标系
            xc, yc, zc = pixel2camera_pose(cx, cy, z, K)

            # 3. 正运动学
            current_jnt, current_pose = get_current_joint_and_pose()

            # 4. 相机系 → 基坐标系（目标位姿）
            target_pose = object_to_base(xc, yc, zc, current_pose)

            # 5. 逆运动学求解
            ik_res = solve_ik(current_jnt, target_pose)
            ik_success = (ik_res[0] == 0)
            target_jnt = ik_res[1] if ik_success else [0.0]*6

            print(f"✅ 正解位姿: {[round(i,3) for i in target_pose]}")
            print(f"✅ 逆解状态: {ik_success} | 关节: {[round(i,3) for i in target_jnt]}")

            # ===================== 发送报文1：正运动学位姿 =====================
            msg1 = {
                "target_obj": obj_name,
                "target_pose": {
                    "x": target_pose[0] / 1000,
                    "y": target_pose[1] / 1000,
                    "z": target_pose[2] / 1000,
                    "roll": target_pose[3],
                    "pitch": target_pose[4],
                    "yaw": target_pose[5]
                }
            }
            await websocket.send(json.dumps(msg1))

            # ===================== 发送报文2：逆解状态 + 关节 =====================
            msg2 = {
                "target_obj": obj_name,
                "ik_solve": ik_success,
                "joint_count": 6,
                "joint_value": target_jnt
            }
            await websocket.send(json.dumps(msg2))

            print("📤 已回传双报文完成")

        except Exception as e:
            print("❌ 异常:", e)
            break

async def main():
    async with websockets.serve(handle_client, HOST, PORT):
        print(f"\n🌐 视觉处理服务运行：ws://{HOST}:{PORT}")
        print("🔄 自动接收 → 自动计算 → 自动回传\n")
        await asyncio.Future()

# ===================== 启动 =====================
if __name__ == "__main__":
    init_robot()
    try:
        asyncio.run(main())
    except KeyboardInterrupt:
        robot.disable_robot()
        robot.power_off()
        robot.logout()


