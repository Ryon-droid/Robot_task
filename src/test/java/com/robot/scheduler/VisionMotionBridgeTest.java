package com.robot.scheduler;

import org.java_websocket.WebSocket;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.handshake.ServerHandshake;
import org.java_websocket.server.WebSocketServer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.net.URI;
import java.util.Scanner;

/**
 * 视觉-运动学桥接测试程序（后端自用，不启动主程序）
 *
 * 作用：
 * 1. 作为 WebSocket 服务端暴露 ws://IP:PORT/ws/vision，接收视觉端识别结果
 * 2. 将视觉数据按照机械臂解算端格式转发（WebSocket 客户端）
 * 3. 接收并打印机械臂解算端回传的正/逆运动学双报文
 * 4. 向视觉端回传 ACK
 *
 * 用法：直接运行 main 方法
 * 参数：[视觉监听IP] [视觉监听端口] [机械臂IP] [机械臂端口]
 * （默认视觉 0.0.0.0:9080，机械臂 172.16.25.46:8081）
 */
public class VisionMotionBridgeTest extends WebSocketServer {

    private static final ObjectMapper mapper = new ObjectMapper();

    private WebSocketClient motionClient;
    private final String motionHost;
    private final int motionPort;
    private volatile boolean motionConnected = false;

    public VisionMotionBridgeTest(String host, int port, String motionHost, int motionPort) {
        super(new InetSocketAddress(host, port));
        this.motionHost = motionHost;
        this.motionPort = motionPort;
    }

    @Override
    public void onStart() {
        System.out.println("[桥接测试] 视觉服务端已启动: ws://" + getAddress() + "/ws/vision");
        System.out.println("[桥接测试] 等待视觉端连接并发送数据...");
        connectToMotionServer();
    }

    private void connectToMotionServer() {
        String url = "ws://" + motionHost + ":" + motionPort;
        System.out.println("[桥接测试] 正在连接机械臂解算端: " + url);
        try {
            motionClient = new WebSocketClient(new URI(url)) {
                @Override
                public void onOpen(ServerHandshake handshake) {
                    System.out.println("[桥接测试] 机械臂解算端连接成功");
                    motionConnected = true;
                }

                @Override
                public void onMessage(String message) {
                    if (message.contains("target_pose")) {
                        System.out.println("[桥接测试] 收到机械臂【正运动学】报文: " + message);
                    } else if (message.contains("ik_solve")) {
                        System.out.println("[桥接测试] 收到机械臂【逆运动学】报文: " + message);
                    } else {
                        System.out.println("[桥接测试] 收到机械臂未知报文: " + message);
                    }
                }

                @Override
                public void onClose(int code, String reason, boolean remote) {
                    System.out.println("[桥接测试] 机械臂解算端连接关闭: code=" + code + ", reason=" + reason);
                    motionConnected = false;
                }

                @Override
                public void onError(Exception ex) {
                    System.err.println("[桥接测试] 机械臂解算端错误: " + ex.getMessage());
                }
            };
            motionClient.connect();
        } catch (Exception e) {
            System.err.println("[桥接测试] 连接机械臂解算端异常: " + e.getMessage());
        }
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor();
        System.out.println("[桥接测试] 视觉端已连接: " + conn.getRemoteSocketAddress() + " 路径: " + path);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[桥接测试] 视觉端断开: " + reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        // 解析视觉数据（兼容 obj_name / class，x / cx，y / cy）
        String objName = "";
        String xStr = "";
        String yStr = "";
        String zStr = "";
        try {
            JsonNode root = mapper.readTree(message);
            JsonNode node = root.get("obj_name");
            if (node != null && !node.isNull()) objName = node.asText();
            if (objName.isEmpty()) {
                node = root.get("class");
                if (node != null && !node.isNull()) objName = node.asText();
            }
            node = root.get("x");
            if (node != null && !node.isNull()) xStr = node.toString();
            else {
                node = root.get("cx");
                if (node != null && !node.isNull()) xStr = node.toString();
            }
            node = root.get("y");
            if (node != null && !node.isNull()) yStr = node.toString();
            else {
                node = root.get("cy");
                if (node != null && !node.isNull()) yStr = node.toString();
            }
            node = root.get("z");
            if (node != null && !node.isNull()) zStr = node.toString();
        } catch (Exception e) {
            System.err.println("[桥接测试] JSON解析失败: " + e.getMessage());
        }

        // 打印摘要，避免 base64 刷屏
        StringBuilder summary = new StringBuilder("[桥接测试] 收到消息");
        if (!objName.isEmpty()) summary.append(" | class: ").append(objName);
        if (!xStr.isEmpty()) summary.append(" | x: ").append(xStr);
        if (!yStr.isEmpty()) summary.append(" | y: ").append(yStr);
        if (!zStr.isEmpty()) summary.append(" | z: ").append(zStr);
        summary.append(" | length: ").append(message.length());
        System.out.println(summary.toString());

        if (!objName.isEmpty()) {
            System.out.println("[桥接测试] 解析成功 → obj_name/class: " + objName);
        }

        // 转发给机械臂解算端
        if (motionClient != null && motionConnected) {
            // 与机械臂解算端对齐格式：
            // {"obj_name":"xxx","x":320,"y":240,"z":0.680}
            String x = xStr.isEmpty() ? "0" : xStr;
            String y = yStr.isEmpty() ? "0" : yStr;
            String z = zStr.isEmpty() ? "0.0" : zStr;
            String forwardStr = String.format(
                "{\"obj_name\":\"%s\",\"x\":%s,\"y\":%s,\"z\":%s}",
                objName, x, y, z
            );

            motionClient.send(forwardStr);
            System.out.println("[桥接测试] 已转发给机械臂解算端: " + forwardStr);
        } else {
            System.out.println("[桥接测试] 机械臂解算端未连接，无法转发");
        }

        // 回传 ACK 给视觉端
        conn.send("{\"ack\":true}");
        System.out.println("[桥接测试] 已向视觉端回复ACK");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[桥接测试] 视觉服务端错误: " + ex.getMessage());
    }

    public static void main(String[] args) {
        String visionHost = args.length > 0 ? args[0] : "0.0.0.0";
        int visionPort    = args.length > 1 ? Integer.parseInt(args[1]) : 9080;
        String motionHost = args.length > 2 ? args[2] : "172.16.25.46";
        int motionPort    = args.length > 3 ? Integer.parseInt(args[3]) : 8081;

        VisionMotionBridgeTest server = new VisionMotionBridgeTest(visionHost, visionPort, motionHost, motionPort);
        server.start();

        System.out.println("[桥接测试] 按 Enter 停止");
        new Scanner(System.in).nextLine();

        try {
            if (server.motionClient != null) {
                server.motionClient.close();
            }
            server.stop(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("[桥接测试] 已停止");
    }
}
