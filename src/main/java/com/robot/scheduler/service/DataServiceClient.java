package com.robot.scheduler.service;

import com.robot.scheduler.entity.Log;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.Task;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 数据服务（S-17）上报客户端
 * 所有上报方法均为异步，不阻塞调度主流程
 */
@Slf4j
@Component
public class DataServiceClient {

    @Value("${data-service.url:http://localhost:8000}")
    private String baseUrl;

    @Value("${data-service.retry.max-attempts:3}")
    private int maxAttempts;

    @Value("${data-service.retry.backoff-multiplier:1000}")
    private long backoffMultiplier;

    private final RestTemplate restTemplate = new RestTemplate();

    /**
     * 通用 POST 带重试（指数退避）
     */
    private void postWithRetry(String path, Map<String, Object> body) {
        executeWithRetry(path, body, "POST");
    }

    /**
     * 通用 PUT 带重试（指数退避）
     */
    private void putWithRetry(String path, Map<String, Object> body) {
        executeWithRetry(path, body, "PUT");
    }

    private void executeWithRetry(String path, Map<String, Object> body, String method) {
        body.put("request_id", UUID.randomUUID().toString().replace("-", ""));

        String url = baseUrl + path;
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        HttpEntity<Map<String, Object>> request = new HttpEntity<>(body, headers);

        int attempt = 0;
        while (attempt < maxAttempts) {
            try {
                if ("PUT".equals(method)) {
                    restTemplate.put(url, request);
                } else {
                    restTemplate.postForEntity(url, request, String.class);
                }
                log.debug("数据服务上报成功: {} {}, attempt={}", method, path, attempt + 1);
                return;
            } catch (ResourceAccessException e) {
                attempt++;
                log.warn("数据服务上报失败({}/{}): {} {} - {}", attempt, maxAttempts, method, path, e.getMessage());
                if (attempt >= maxAttempts) {
                    log.error("数据服务上报最终失败，已放弃: {} {}", method, path);
                    return;
                }
                try {
                    Thread.sleep((long) Math.pow(2, attempt) * backoffMultiplier);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    return;
                }
            } catch (Exception e) {
                log.error("数据服务上报异常，不再重试: {} {} - {}", method, path, e.getMessage());
                return;
            }
        }
    }

    // ==================== 任务相关上报 ====================

    @Async("dataServiceExecutor")
    public void reportTaskCreated(Task task) {
        Map<String, Object> body = new HashMap<>();
        body.put("task_id", task.getTaskId());
        body.put("task_name", task.getTaskName());
        body.put("command_type", task.getCommandType());
        body.put("priority", task.getPriority());
        body.put("robot_id", task.getRobotId());
        body.put("robot_code", task.getRobotCode());
        body.put("status", task.getStatus());
        body.put("task_params", task.getTaskParams());
        body.put("deadline", task.getDeadline());
        body.put("estimated_duration", task.getEstimatedDuration());
        body.put("dynamic_priority_score", task.getDynamicPriorityScore());
        postWithRetry("/api/v1/tasks", body);
    }

    @Async("dataServiceExecutor")
    public void reportTaskStatusChanged(String taskId, String oldStatus, String newStatus, String reason) {
        Map<String, Object> body = new HashMap<>();
        body.put("status", newStatus);
        body.put("old_status", oldStatus);
        body.put("change_reason", reason);
        postWithRetry("/api/v1/tasks/" + taskId + "/status", body);
    }

    @Async("dataServiceExecutor")
    public void reportTaskUpdated(String taskId, String robotId, String status, Double dynamicPriorityScore) {
        Map<String, Object> body = new HashMap<>();
        body.put("robot_id", robotId);
        body.put("status", status);
        body.put("dynamic_priority_score", dynamicPriorityScore);
        putWithRetry("/api/v1/tasks/" + taskId, body);
    }

    // ==================== 日志相关上报 ====================

    @Async("dataServiceExecutor")
    public void reportLog(Log logEntity) {
        String path = "ERROR".equalsIgnoreCase(logEntity.getLogType())
                ? "/api/v1/logs/error"
                : "/api/v1/logs/operation";
        Map<String, Object> body = new HashMap<>();
        body.put("log_type", logEntity.getLogType());
        body.put("message", logEntity.getMessage());
        body.put("reference_id", logEntity.getReferenceId());
        postWithRetry(path, body);
    }

    // ==================== 机器人相关上报 ====================

    @Async("dataServiceExecutor")
    public void reportRobotStatus(Robot robot) {
        Map<String, Object> body = new HashMap<>();
        body.put("robot_id", robot.getRobotId());
        body.put("status", robot.getStatus());
        body.put("battery", robot.getBattery());
        body.put("load", robot.getLoad());
        postWithRetry("/api/v1/robots/status", body);
    }

    @Async("dataServiceExecutor")
    public void reportRobotPosition(String robotId, Double x, Double y, Double yaw) {
        Map<String, Object> body = new HashMap<>();
        body.put("robot_id", robotId);
        body.put("x", x);
        body.put("y", y);
        body.put("yaw", yaw);
        postWithRetry("/api/v1/navigation/positions", body);
    }

    @Async("dataServiceExecutor")
    public void reportRobotHeartbeat(String robotId) {
        Map<String, Object> body = new HashMap<>();
        body.put("robot_id", robotId);
        body.put("timestamp", System.currentTimeMillis());
        postWithRetry("/api/v1/robots/heartbeat", body);
    }
}
