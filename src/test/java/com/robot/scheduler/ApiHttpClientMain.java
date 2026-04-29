package com.robot.scheduler;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

/**
 * 独立 HTTP 客户端测试程序 —— 不依赖 Spring Boot 启动
 * 直接调用后端接口，验证任务创建与查询是否正常
 *
 * 运行方式：
 * 1. IDE 直接运行 main()
 * 2. 命令行：mvn -q exec:java -Dexec.mainClass=com.robot.scheduler.ApiHttpClientMain -Dexec.classpathScope=test
 * 3. 测试远程服务器时传参：java com.robot.scheduler.ApiHttpClientMain http://192.168.1.100:8080
 */
public class ApiHttpClientMain {

    private static final String DEFAULT_BASE_URL = "http://localhost:8080";

    private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();

    public static void main(String[] args) throws Exception {
        String baseUrl = (args.length > 0) ? args[0].replaceAll("/$", "") : DEFAULT_BASE_URL;

        System.out.println("==================================================");
        System.out.println("  API 任务接口测试客户端");
        System.out.println("  目标地址: " + baseUrl);
        System.out.println("==================================================\n");

        // TEST 1: 创建任务
        System.out.println("[TEST 1] 创建任务 (POST /api/v1/tasks)");
        String taskPayload = "{"
                + "\"robotId\":\"r001\","
                + "\"commandType\":\"MOVE_TO\","
                + "\"priority\":1,"
                + "\"params\":{\"x\":20,\"y\":8,\"speed\":1.5},"
                + "\"estimatedDuration\":120"
                + "}";

        String taskResponse = post(baseUrl + "/api/v1/tasks", taskPayload);
        System.out.println(taskResponse);
        checkResponse(taskResponse);
        System.out.println();

        // TEST 2: 查询任务列表
        System.out.println("[TEST 2] 查询任务列表 (GET /api/v1/tasks)");
        String listResponse = get(baseUrl + "/api/v1/tasks");
        System.out.println(listResponse + "\n");

        System.out.println("==================================================");
        System.out.println("  测试执行完毕");
        System.out.println("==================================================");
    }

    private static String post(String url, String jsonBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/json")
                .timeout(Duration.ofSeconds(10))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return formatResponse(response);
    }

    private static String get(String url) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Accept", "application/json")
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();

        HttpResponse<String> response = HTTP_CLIENT.send(request,
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        return formatResponse(response);
    }

    private static String formatResponse(HttpResponse<String> response) {
        return "HTTP Status: " + response.statusCode() + "\nBody: " + response.body();
    }

    private static void checkResponse(String responseText) {
        if (responseText.contains("\"code\":200") || responseText.contains("\"code\": 200")) {
            System.out.println("✅ 响应正常");
        } else {
            System.out.println("⚠️ 响应异常，请检查后端日志或数据库数据");
        }
    }
}
