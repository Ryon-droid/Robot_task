package com.robot.scheduler.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.common.BusinessException;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.LLMService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

@Service
public class LLMServiceImpl implements LLMService {

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${llm.websocket.url:ws://localhost:8090/ws/llm}")
    private String llmWebSocketUrl;

    @Value("${llm.websocket.timeout-ms:5000}")
    private long llmWebSocketTimeoutMs;

    @Override
    public List<Task> parseNaturalLanguage(String instruction) {
        Map<String, Object> data = new HashMap<>();
        data.put("instruction", instruction);
        Map<String, Object> wsResponse = sendWsRequest("parse_natural_language", data);

        Map<String, Object> responseData = extractDataMap(wsResponse, null);
        Map<String, Object> structuredPlan = normalizeStructuredPlan(responseData);

        if (structuredPlan.isEmpty()) {
            throw new BusinessException(422, "LLM返回格式不合法：必须包含 target_object 和 task_list");
        }

        String targetObject = readString(structuredPlan, "target_object", "未知目标");
        List<Map<String, Object>> taskList = readTaskList(structuredPlan.get("task_list"));

        if (taskList.isEmpty()) {
            throw new BusinessException(422, "LLM返回的任务列表为空");
        }

        List<Task> tasks = new ArrayList<>();
        for (Map<String, Object> item : taskList) {
            Task task = buildSubTask(targetObject, item);
            tasks.add(task);
        }
        return tasks;
    }

    /**
     * 将 LLM 返回的 task_list 单项转换为 Task 实体
     */
    private Task buildSubTask(String targetObject, Map<String, Object> item) {
        String device = readString(item, "device", "");
        String action = readString(item, "action", "");
        String target = readString(item, "target", "");

        Task task = new Task();
        task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        task.setTaskName(targetObject + "-" + device + "-" + action);
        task.setCommandType(action.toUpperCase());
        task.setPriority(3);
        task.setStatus(StatusConstant.TASK_STATUS_PENDING);
        task.setRobotCode(device);
        task.setTaskParams(writeJson(item, "{}"));

        // 解析坐标参数（如果 target 包含坐标信息或 item 中有 x/y）
        Map<String, Object> params = new HashMap<>(item);
        Object x = item.get("x");
        Object y = item.get("y");
        if (x != null) params.put("x", x);
        if (y != null) params.put("y", y);
        params.put("target_object", targetObject);
        params.put("target", target);
        task.setTaskParams(writeJson(params, "{}"));

        return task;
    }

    @Override
    public Map<String, Object> combineTasks(Map<String, Object> taskData) {
        return extractDataMap(sendWsRequest("combine_tasks", taskData), null);
    }

    @Override
    public Map<String, Object> getBehaviorTreeStatus() {
        return extractDataMap(sendWsRequest("get_behavior_tree_status", new HashMap<>()), null);
    }

    @Override
    public Map<String, Object> executeBehaviorNode(Map<String, Object> behaviorData) {
        return extractDataMap(sendWsRequest("execute_behavior_node", behaviorData), null);
    }

    private Map<String, Object> sendWsRequest(String action, Map<String, Object> data) {
        String requestId = UUID.randomUUID().toString().replace("-", "");
        CompletableFuture<Map<String, Object>> responseFuture = new CompletableFuture<>();

        TextWebSocketHandler responseHandler = new TextWebSocketHandler() {
            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                Map<String, Object> response = objectMapper.readValue(message.getPayload(), new TypeReference<Map<String, Object>>() {
                });
                Object respRequestId = response.get("requestId");
                if (respRequestId == null || requestId.equals(String.valueOf(respRequestId))) {
                    responseFuture.complete(response);
                }
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                responseFuture.completeExceptionally(exception);
            }
        };

        StandardWebSocketClient client = new StandardWebSocketClient();
        WebSocketSession session = null;
        try {
            session = client.doHandshake(responseHandler, new WebSocketHttpHeaders(), new URI(llmWebSocketUrl))
                    .get(llmWebSocketTimeoutMs, TimeUnit.MILLISECONDS);

            Map<String, Object> request = new HashMap<>();
            request.put("requestId", requestId);
            request.put("action", action);
            request.put("data", data == null ? new HashMap<>() : data);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(request)));

            return responseFuture.get(llmWebSocketTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (Exception e) {
            throw new BusinessException(502, "调用LLM模块WebSocket失败: " + e.getMessage());
        } finally {
            if (session != null && session.isOpen()) {
                try {
                    session.close();
                } catch (Exception ignored) {
                }
            }
        }
    }

    private Map<String, Object> extractDataMap(Map<String, Object> wsResponse, String nestedKey) {
        if (wsResponse == null) {
            return new HashMap<>();
        }

        Object dataObj = wsResponse.get("data");
        Map<String, Object> baseMap = dataObj instanceof Map ? castToMap(dataObj) : wsResponse;

        if (nestedKey == null) {
            return baseMap;
        }

        Object nestedObj = baseMap.get(nestedKey);
        if (nestedObj instanceof Map) {
            return castToMap(nestedObj);
        }
        return baseMap;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object value) {
        return (Map<String, Object>) value;
    }

    private String readString(Map<String, Object> map, String key, String defaultValue) {
        Object val = map.get(key);
        return val == null ? defaultValue : String.valueOf(val);
    }

    private Map<String, Object> normalizeStructuredPlan(Map<String, Object> responseData) {
        if (responseData == null || responseData.isEmpty()) {
            return new HashMap<>();
        }

        // 场景1：data 直接是 {target_object, task_list}
        if (hasStructuredPlanKeys(responseData)) {
            return toSnakeCasePlan(responseData);
        }

        // 场景2：data.plan / data.result 内嵌结构化任务
        Object plan = responseData.get("plan");
        if (plan instanceof Map && hasStructuredPlanKeys(castToMap(plan))) {
            return toSnakeCasePlan(castToMap(plan));
        }

        Object result = responseData.get("result");
        if (result instanceof Map && hasStructuredPlanKeys(castToMap(result))) {
            return toSnakeCasePlan(castToMap(result));
        }

        return new HashMap<>();
    }

    private boolean hasStructuredPlanKeys(Map<String, Object> map) {
        return map.containsKey("target_object") || map.containsKey("targetObject")
                || map.containsKey("task_list") || map.containsKey("taskList");
    }

    private Map<String, Object> toSnakeCasePlan(Map<String, Object> rawPlan) {
        Map<String, Object> normalized = new HashMap<>();
        Object targetObj = rawPlan.containsKey("target_object") ? rawPlan.get("target_object") : rawPlan.get("targetObject");
        Object taskListObj = rawPlan.containsKey("task_list") ? rawPlan.get("task_list") : rawPlan.get("taskList");
        normalized.put("target_object", targetObj == null ? "" : String.valueOf(targetObj));
        normalized.put("task_list", readTaskList(taskListObj));
        return normalized;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> readTaskList(Object value) {
        if (value instanceof List) {
            return (List<Map<String, Object>>) value;
        }
        return List.of();
    }

    private String writeJson(Map<String, Object> map, String defaultJson) {
        try {
            return objectMapper.writeValueAsString(map);
        } catch (Exception e) {
            return defaultJson;
        }
    }
}
