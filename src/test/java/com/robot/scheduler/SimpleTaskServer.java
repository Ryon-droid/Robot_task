package com.robot.scheduler;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * Minimal task server — no Spring Boot, no database.
 * Receives tasks from frontend (POST) and sends task list to frontend (GET).
 *
 * Run: IDE -> right click -> Run 'SimpleTaskServer.main()'
 */
public class SimpleTaskServer {

    // In-memory task list (simulated, no database)
    private static final List<Map<String, Object>> TASKS = new ArrayList<>();

    static {
        // Pre-populate with simulated tasks
        TASKS.add(createTask("MOVE", "R-001", 2, 10.0));
        TASKS.add(createTask("CHARGE", "R-001", 1, 65.04516666666666));
        TASKS.add(createTask("CLEAN", "R-002", 2, 80.0452));
        TASKS.add(createTask("PATROL", "R-004", 3, 90.04376666666667));
    }

    private static Map<String, Object> createTask(String commandType, String robotId, int priority, double urgency) {
        Map<String, Object> task = new LinkedHashMap<>();
        task.put("commandType", commandType);
        task.put("robotId", robotId);
        task.put("priority", priority);
        task.put("urgency", urgency);
        return task;
    }

    public static void main(String[] args) throws IOException {
        int port = 8080;
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        server.createContext("/api/v1/tasks", exchange -> {
            // CORS preflight
            if ("OPTIONS".equalsIgnoreCase(exchange.getRequestMethod())) {
                exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
                exchange.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, PUT, PATCH, DELETE, OPTIONS");
                exchange.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
                exchange.sendResponseHeaders(204, -1);
                return;
            }

            String method = exchange.getRequestMethod().toUpperCase();

            if ("POST".equals(method) || "PUT".equals(method)) {
                // Receive task from frontend
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String taskId = UUID.randomUUID().toString().replace("-", "");

                String robotId = extractValue(body, "robotId");
                String commandType = extractValue(body, "commandType");
                String priorityStr = extractValue(body, "priority");
                String urgencyStr = extractValue(body, "urgency");
                String params = extractObject(body, "params");
                String estimatedDuration = extractValue(body, "estimatedDuration");
                String deadline = extractValue(body, "deadline");

                int priority = priorityStr.isEmpty() ? 3 : Integer.parseInt(priorityStr);
                double urgency = urgencyStr.isEmpty() ? Math.random() * 100 : Double.parseDouble(urgencyStr);

                // Add to in-memory list
                Map<String, Object> newTask = createTask(
                        commandType.isEmpty() ? "UNKNOWN" : commandType,
                        robotId.isEmpty() ? "(auto)" : robotId,
                        priority,
                        urgency
                );
                newTask.put("taskId", taskId);
                TASKS.add(newTask);

                // Print to terminal
                System.out.println("\n========================================");
                System.out.println("[TASK RECEIVED] " + java.time.LocalDateTime.now());
                System.out.println("taskId       : " + taskId);
                System.out.println("robotId      : " + robotId);
                System.out.println("commandType  : " + commandType);
                System.out.println("priority     : " + priority);
                System.out.println("urgency      : " + urgency);
                System.out.println("params       : " + (params.isEmpty() ? "{}" : params));
                if (!estimatedDuration.isEmpty()) System.out.println("duration     : " + estimatedDuration + "s");
                if (!deadline.isEmpty()) System.out.println("deadline     : " + deadline);
                System.out.println("rawBody      : " + body);
                System.out.println("========================================\n");

                String response = "{"
                        + "\"code\":200,"
                        + "\"message\":\"\u64CD\u4F5C\u6210\u529F\","
                        + "\"data\":{"
                        + "\"taskId\":\"" + taskId + "\""
                        + "}"
                        + "}";
                sendJson(exchange, 200, response);

            } else if ("GET".equals(method)) {
                // Send task list to frontend
                StringBuilder sb = new StringBuilder();
                sb.append("[");
                for (int i = 0; i < TASKS.size(); i++) {
                    Map<String, Object> task = TASKS.get(i);
                    sb.append("{");
                    sb.append("\"commandType\":\"").append(task.get("commandType")).append("\",");
                    sb.append("\"robotId\":\"").append(task.get("robotId")).append("\",");
                    sb.append("\"priority\":").append(task.get("priority")).append(",");
                    sb.append("\"urgency\":").append(task.get("urgency"));
                    sb.append("}");
                    if (i < TASKS.size() - 1) sb.append(",");
                }
                sb.append("]");

                String response = "{"
                        + "\"code\":200,"
                        + "\"message\":\"\u64CD\u4F5C\u6210\u529F\","
                        + "\"data\":" + sb.toString()
                        + "}";
                sendJson(exchange, 200, response);

            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        });

        server.setExecutor(null);
        server.start();

        System.out.println("Server started at http://0.0.0.0:" + port + "/api/v1/tasks");
        System.out.println("  POST /api/v1/tasks -> receive task from frontend");
        System.out.println("  GET  /api/v1/tasks -> send task list to frontend");
        System.out.println("Press Ctrl+C to stop\n");
    }

    private static void sendJson(HttpExchange exchange, int status, String json) throws IOException {
        byte[] bytes = json.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream os = exchange.getResponseBody()) {
            os.write(bytes);
        }
    }

    private static String extractValue(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return "";
        int colon = json.indexOf(':', idx);
        if (colon == -1) return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start < json.length() && json.charAt(start) == '"') {
            start++;
            int end = json.indexOf('"', start);
            while (end != -1 && json.charAt(end - 1) == '\\') end = json.indexOf('"', end + 1);
            return end == -1 ? "" : json.substring(start, end);
        } else {
            int end = start;
            while (end < json.length() && ",}\n\r\t".indexOf(json.charAt(end)) == -1) end++;
            return json.substring(start, end).trim();
        }
    }

    private static String extractObject(String json, String key) {
        int idx = json.indexOf("\"" + key + "\"");
        if (idx == -1) return "";
        int colon = json.indexOf(':', idx);
        if (colon == -1) return "";
        int start = colon + 1;
        while (start < json.length() && Character.isWhitespace(json.charAt(start))) start++;
        if (start >= json.length() || json.charAt(start) != '{') return "";
        int braceCount = 0;
        int i = start;
        for (; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') braceCount++;
            else if (c == '}') {
                braceCount--;
                if (braceCount == 0) break;
            }
        }
        return json.substring(start, i + 1);
    }
}
