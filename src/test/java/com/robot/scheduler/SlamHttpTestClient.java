package com.robot.scheduler;

import java.io.*;
import java.net.ConnectException;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.NoRouteToHostException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * SLAM HTTP 通信测试客户端（适配 http_scheduler_bridge.py）
 *
 * 作用：作为 HTTP 客户端连接 SLAM 端，测试其提供的 REST API。
 *
 * SLAM 端提供的端点：
 *   GET  /healthz                       -> 健康检查
 *   GET  /api/v1/scheduler/ros/pose     -> 获取机器人位姿
 *   GET  /api/v1/scheduler/ros/goal     -> 获取最新已发布的导航目标
 *   POST /api/v1/scheduler/ros/goal     -> 发送导航目标（body 支持 {x, y, yaw} 简写格式）
 *
 * 用法: 直接运行 main 方法
 * 参数: [IP] [端口]（默认 172.16.25.219 9090）
 */
public class SlamHttpTestClient {

    private static final int DEFAULT_CONNECT_TIMEOUT_MS = 5000;
    private static final int DEFAULT_READ_TIMEOUT_MS = 5000;

    public static void main(String[] args) {
        String host = args.length > 0 ? args[0] : "172.16.25.219";
        Integer portArg = args.length > 1 ? Integer.parseInt(args[1]) : null;
        String scheme = args.length > 2 ? args[2] : "http";

        String resolvedIp = resolveHostIp(host);
        if (resolvedIp != null) {
            System.out.println("[解析] host=" + host + " -> " + resolvedIp);
        }

        int port = selectPort(host, portArg, scheme);
        String baseUrl = scheme + "://" + host + ":" + port;

        System.out.println("========================================");
        System.out.println("[SLAM HTTP 测试] 目标地址: " + baseUrl);
        System.out.println("[SLAM HTTP 测试] 测试 http_scheduler_bridge.py 提供的 HTTP API");
        System.out.println("========================================\n");

        // 1. 健康检查
        testGet(baseUrl, "/healthz", "健康检查");

        // 2. 查询位姿
        testGet(baseUrl, "/api/v1/scheduler/ros/pose", "位姿查询");

        // 3. 查询最新目标
        testGet(baseUrl, "/api/v1/scheduler/ros/goal", "目标查询(GET)");

        // 4. 发送导航目标（简写格式 {x, y, yaw}，Python 端支持）
        String goalBody = "{\"x\":2.5,\"y\":3.0,\"yaw\":1.57}";
        testPost(baseUrl, "/api/v1/scheduler/ros/goal", goalBody, "发送导航目标(POST)");

        // 5. 再次查询最新目标，验证是否写入
        testGet(baseUrl, "/api/v1/scheduler/ros/goal", "目标查询(验证)");

        System.out.println("\n========================================");
        System.out.println("[SLAM HTTP 测试] 全部接口测试完成");
        System.out.println("========================================");
    }

    private static void testGet(String baseUrl, String path, String desc) {
        String url = baseUrl + path;
        System.out.println("[" + desc + "] GET " + url);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_READ_TIMEOUT_MS);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SlamHttpTestClient/1.1");

            int code = conn.getResponseCode();
            String body = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            System.out.println("  响应码: " + code);
            System.out.println("  响应体: " + truncate(body, 400));
        } catch (Exception e) {
            System.err.println("  请求失败: " + summarizeException(e));
            printHints(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        System.out.println();
    }

    private static void testPost(String baseUrl, String path, String jsonBody, String desc) {
        String url = baseUrl + path;
        System.out.println("[" + desc + "] POST " + url);
        System.out.println("  请求体: " + jsonBody);
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("POST");
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent", "SlamHttpTestClient/1.1");
            conn.setConnectTimeout(DEFAULT_CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(DEFAULT_READ_TIMEOUT_MS);

            try (OutputStream os = conn.getOutputStream()) {
                os.write(jsonBody.getBytes(StandardCharsets.UTF_8));
            }

            int code = conn.getResponseCode();
            String body = readStream(code < 400 ? conn.getInputStream() : conn.getErrorStream());
            System.out.println("  响应码: " + code);
            System.out.println("  响应体: " + truncate(body, 400));
        } catch (Exception e) {
            System.err.println("  请求失败: " + summarizeException(e));
            printHints(e);
        } finally {
            if (conn != null) conn.disconnect();
        }
        System.out.println();
    }

    private static int selectPort(String host, Integer portArg, String scheme) {
        if (portArg != null) {
            return portArg;
        }

        int[] candidates = new int[] {8080, 9090};
        for (int port : candidates) {
            ProbeResult probe = probeHealthz(scheme, host, port);
            if (probe.httpCode != null) {
                System.out.println("[探测] /healthz 在端口 " + port + " 返回 HTTP " + probe.httpCode);
                return port;
            }
            System.out.println("[探测] 端口 " + port + " 失败: " + summarizeException(probe.exception));
        }

        System.out.println("[探测] 8080/9090 都未成功，默认使用 8080 继续跑（便于输出更多错误信息）");
        return 8080;
    }

    private static ProbeResult probeHealthz(String scheme, String host, int port) {
        String url = scheme + "://" + host + ":" + port + "/healthz";
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(url).openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(1500);
            conn.setReadTimeout(1500);
            conn.setRequestProperty("Accept", "application/json");
            int code = conn.getResponseCode();
            return new ProbeResult(code, null);
        } catch (Exception e) {
            return new ProbeResult(null, e);
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    private static String resolveHostIp(String host) {
        try {
            return InetAddress.getByName(host).getHostAddress();
        } catch (Exception e) {
            return null;
        }
    }

    private static String summarizeException(Exception e) {
        if (e == null) return "(no exception)";
        StringBuilder sb = new StringBuilder();
        sb.append(e.getClass().getSimpleName());
        if (e.getMessage() != null && !e.getMessage().isBlank()) {
            sb.append(": ").append(e.getMessage());
        }
        Throwable cause = e.getCause();
        int depth = 0;
        while (cause != null && depth < 4) {
            sb.append(" <- ").append(cause.getClass().getSimpleName());
            if (cause.getMessage() != null && !cause.getMessage().isBlank()) {
                sb.append(": ").append(cause.getMessage());
            }
            cause = cause.getCause();
            depth++;
        }
        return sb.toString();
    }

    private static void printHints(Exception e) {
        if (e == null) return;

        if (e instanceof UnknownHostException) {
            System.err.println("  提示: 域名解析失败；检查 host 是否写错或 DNS 不可用。");
            return;
        }
        if (e instanceof NoRouteToHostException) {
            System.err.println("  提示: 无路由到主机；检查本机到目标网段的路由/网卡/网关配置。");
            return;
        }
        if (e instanceof ConnectException) {
            System.err.println("  提示: 连接被拒绝/无法建立；常见原因是端口没开、服务没启动、或防火墙拦截。"
                    + " 另外确认服务端监听不是 127.0.0.1（需要 0.0.0.0 才能被远程访问）。");
            return;
        }
        if (e instanceof SocketTimeoutException) {
            System.err.println("  提示: 连接或读取超时；常见原因是网络不通/被防火墙丢包/目标IP不对/端口被过滤。"
                    + " 先用 curl/nc 在同一台机器验证连通性。");
        }
    }

    private static final class ProbeResult {
        final Integer httpCode;
        final Exception exception;

        ProbeResult(Integer httpCode, Exception exception) {
            this.httpCode = httpCode;
            this.exception = exception;
        }
    }

    private static String readStream(InputStream is) {
        if (is == null) return "";
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = reader.readLine()) != null) {
                sb.append(line);
            }
            return sb.toString();
        } catch (IOException e) {
            return "";
        }
    }

    private static String truncate(String s, int maxLen) {
        if (s == null) return "";
        if (s.length() <= maxLen) return s;
        return s.substring(0, maxLen) + "... (共 " + s.length() + " 字符)";
    }
}