package com.robot.scheduler;

import org.java_websocket.WebSocket;
import org.java_websocket.handshake.ClientHandshake;
import org.java_websocket.server.WebSocketServer;

import java.net.InetSocketAddress;
import java.util.Scanner;

/**
 * 视觉通信测试服务端（后端自用，不启动主程序）
 *
 * 作用：独立启动一个 WebSocket 服务端，暴露 ws://IP:PORT/ws/vision，
 * 等待真实的视觉识别端连接并推送数据，验证能否成功收到消息。
 *
 * 用法：直接运行 main 方法，告诉视觉端同学连接本机的 ws://IP:PORT/ws/vision
 * 参数：[监听IP] [监听端口]（默认 0.0.0.0 9080）
 * mvn exec:java -Dexec.mainClass="com.robot.scheduler.VisionCommTestServer" -Dexec.classpathScope=test
 */
public class VisionCommTestServer extends WebSocketServer {

    public VisionCommTestServer(String host, int port) {
        super(new InetSocketAddress(host, port));
    }

    @Override
    public void onOpen(WebSocket conn, ClientHandshake handshake) {
        String path = handshake.getResourceDescriptor();
        System.out.println("[测试服务端] 视觉端已连接: " + conn.getRemoteSocketAddress() + " 路径: " + path);
    }

    @Override
    public void onClose(WebSocket conn, int code, String reason, boolean remote) {
        System.out.println("[测试服务端] 视觉端断开: " + reason);
    }

    @Override
    public void onMessage(WebSocket conn, String message) {
        System.out.println("[测试服务端] 收到消息: " + message);

        // 简单解析 obj_name
        String objName = extractField(message, "obj_name");
        if (objName.isEmpty()) {
            objName = extractField(message, "class");
        }
        if (!objName.isEmpty()) {
            System.out.println("[测试服务端] 解析成功 → obj_name/class: " + objName);
        }

        // 回传 ACK（与真实后端保持一致）
        conn.send("{\"ack\":true}");
        System.out.println("[测试服务端] 已回复ACK");
    }

    @Override
    public void onError(WebSocket conn, Exception ex) {
        System.err.println("[测试服务端] 错误: " + ex.getMessage());
    }

    @Override
    public void onStart() {
        System.out.println("[测试服务端] 已启动: ws://" + getAddress() + "/ws/vision");
        System.out.println("[测试服务端] 请让视觉端连接上述地址并发送数据");
        System.out.println("[测试服务端] 按 Enter 停止");
    }

    private String extractField(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx < 0) return "";
        int colon = json.indexOf(":", idx);
        int start = json.indexOf("\"", colon);
        if (start < 0) {
            // 可能是数字，不是字符串
            int comma = json.indexOf(",", colon);
            int brace = json.indexOf("}", colon);
            int end = Math.min(comma > 0 ? comma : Integer.MAX_VALUE, brace > 0 ? brace : Integer.MAX_VALUE);
            return json.substring(colon + 1, end).trim();
        }
        int end = json.indexOf("\"", start + 1);
        return (end > start) ? json.substring(start + 1, end) : "";
    }

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "0.0.0.0";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 9080;

        VisionCommTestServer server = new VisionCommTestServer(host, port);
        server.start();

        new Scanner(System.in).nextLine();

        try {
            server.stop(1000);
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        System.out.println("[测试服务端] 已停止");
    }
}
