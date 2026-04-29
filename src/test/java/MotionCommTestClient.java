import org.java_websocket.client.WebSocketClient;
import org.java_websocket.handshake.ServerHandshake;

import java.net.URI;

/**
 * 机械臂解算端通信测试客户端（后端自用，不启动主程序）
 *
 * 作用：作为 WebSocket 客户端连接机械臂解算同学的服务端，
 * 发送虚拟视觉数据，接收并打印回传的正/逆运动学双报文。
 *
 * 用法：直接运行 main 方法
 * 参数：[算法端IP] [算法端端口]（默认 localhost 8081）
 */
public class MotionCommTestClient {

    public static void main(String[] args) throws Exception {
        String host = args.length > 0 ? args[0] : "localhost";
        int port = args.length > 1 ? Integer.parseInt(args[1]) : 8081;
        String url = "ws://" + host + ":" + port + "/ws/motion";

        // 虚拟视觉识别数据（模拟视觉端发给后端的数据）
        String mockVisionData = "{\"obj_name\":\"cup\",\"x\":0.450,\"y\":0.220,\"z\":0.680}";

        System.out.println("[运动学通信测试] 将连接: " + url);

        WebSocketClient client = new WebSocketClient(new URI(url)) {
            @Override
            public void onOpen(ServerHandshake handshake) {
                System.out.println("[运动学通信测试] 连接成功");
                send(mockVisionData);
                System.out.println("[运动学通信测试] 已发送虚拟视觉数据: " + mockVisionData);
                System.out.println("[运动学通信测试] 等待回传双报文...");
            }

            @Override
            public void onMessage(String message) {
                if (message.contains("target_pose")) {
                    System.out.println("[运动学通信测试] 收到【正运动学】报文: " + message);
                } else if (message.contains("ik_solve")) {
                    System.out.println("[运动学通信测试] 收到【逆运动学】报文: " + message);
                } else {
                    System.out.println("[运动学通信测试] 收到未知报文: " + message);
                }
            }

            @Override
            public void onClose(int code, String reason, boolean remote) {
                System.out.println("[运动学通信测试] 连接关闭: code=" + code + ", reason=" + reason);
            }

            @Override
            public void onError(Exception ex) {
                System.err.println("[运动学通信测试] 错误: " + ex.getMessage());
            }
        };

        client.connect();

        // 等待接收双报文（最多10秒）
        long start = System.currentTimeMillis();
        while (client.isOpen() && System.currentTimeMillis() - start < 10000) {
            Thread.sleep(100);
        }

        if (client.isOpen()) {
            System.out.println("[运动学通信测试] 10秒内未收全双报文，超时关闭");
            client.close();
        }
    }
}
