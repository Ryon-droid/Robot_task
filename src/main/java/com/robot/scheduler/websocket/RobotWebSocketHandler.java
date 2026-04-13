package com.robot.scheduler.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.service.RobotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

@Component
public class RobotWebSocketHandler extends TextWebSocketHandler {

    @Autowired
    private RobotService robotService;

    private final ObjectMapper objectMapper = new ObjectMapper();
    
    // 存储所有连接的会话
    private static final List<WebSocketSession> sessions = new CopyOnWriteArrayList<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        sessions.add(session);
        System.out.println("WebSocket连接建立: " + session.getId());
        
        // 立即发送一次当前位置
        broadcastRobotPositions();
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 可以处理前端发送的消息
        String payload = message.getPayload();
        System.out.println("收到消息: " + payload);
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        sessions.remove(session);
        System.out.println("WebSocket连接关闭: " + session.getId());
    }

    /**
     * 广播所有机器人位置
     */
    public void broadcastRobotPositions() {
        if (sessions.isEmpty()) {
            return;
        }

        try {
            List<Robot> robots = robotService.getRobotList();
            Map<String, Object> message = new HashMap<>();
            message.put("type", "robot_positions");
            message.put("timestamp", System.currentTimeMillis());
            
            Map<String, Object> positions = new HashMap<>();
            for (Robot robot : robots) {
                Map<String, Object> pose = new HashMap<>();
                pose.put("x", robot.getX() != null ? robot.getX() : 0.0);
                pose.put("y", robot.getY() != null ? robot.getY() : 0.0);
                pose.put("yaw", robot.getYaw() != null ? robot.getYaw() : 0.0);
                pose.put("status", robot.getStatus());
                positions.put(robot.getRobotId(), pose);
            }
            message.put("data", positions);

            String jsonMessage = objectMapper.writeValueAsString(message);
            TextMessage textMessage = new TextMessage(jsonMessage);

            for (WebSocketSession session : sessions) {
                if (session.isOpen()) {
                    session.sendMessage(textMessage);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}