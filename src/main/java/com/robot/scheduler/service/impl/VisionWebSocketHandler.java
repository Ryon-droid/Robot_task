package com.robot.scheduler.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.service.MotionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;

/**
 * 视觉识别 WebSocket 服务端处理器
 * <p>
 * 接收视觉识别客户端推送的目标物体信息（{obj_name, x, y, z}），
 * 解析后自动转发给运动学算法端。
 */
@Slf4j
@Component
public class VisionWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private MotionService motionService;

    @Autowired
    private ObjectMapper objectMapper;

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("视觉识别客户端已连接: sessionId={}", session.getId());
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("收到视觉识别数据: sessionId={}, payload={}", session.getId(), payload);

        Map<String, Object> data;
        try {
            data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
            });
        } catch (Exception e) {
            log.warn("解析视觉识别数据失败: {}", e.getMessage());
            sendAck(session, false, "invalid json");
            return;
        }

        String objName = String.valueOf(data.getOrDefault("obj_name", ""));
        if (objName.isEmpty()) {
            log.warn("视觉识别数据缺少 obj_name 字段");
            sendAck(session, false, "missing obj_name");
            return;
        }

        double x = parseDouble(data.get("x"), 0.0);
        double y = parseDouble(data.get("y"), 0.0);
        double z = parseDouble(data.get("z"), 0.0);

        boolean sent = motionService.sendTargetObject(objName, x, y, z);
        if (sent) {
            log.info("已将视觉识别数据转发至运动学算法端: obj_name={}, xyz=({},{},{})", objName, x, y, z);
            sendAck(session, true, null);
        } else {
            log.warn("转发视觉识别数据失败，运动学算法端未连接: obj_name={}", objName);
            sendAck(session, false, "motion service not connected");
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("视觉识别客户端断开: sessionId={}, status={}", session.getId(), status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
        log.error("视觉识别客户端传输错误: sessionId={}, error={}", session.getId(), exception.getMessage());
    }

    // ==================== 辅助方法 ====================

    private void sendAck(WebSocketSession session, boolean success, String error) {
        try {
            if (session.isOpen()) {
                String ack = success
                        ? "{\"ack\":true}"
                        : "{\"ack\":false,\"error\":\"" + (error != null ? error : "unknown") + "\"}";
                session.sendMessage(new TextMessage(ack));
            }
        } catch (Exception e) {
            log.warn("回传 ACK 失败: {}", e.getMessage());
        }
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
