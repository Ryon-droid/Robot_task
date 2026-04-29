package com.robot.scheduler.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;

/**
 * LLM WebSocket 原始连通性测试
 * 不依赖 Spring 上下文，直接测试能否与 LLM 服务建立 WebSocket 连接
 */
public class LLMWebSocketRawConnectionTest {

    private static final String LLM_WEBSOCKET_URL = "ws://172.16.25.192:8090/ws/llm";
    private static final long TIMEOUT_SECONDS = 5;

    @Test
    public void testLLMWebSocketConnection() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        CompletableFuture<String> messageFuture = new CompletableFuture<>();

        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                // 连接建立后发送一条测试消息
                session.sendMessage(new TextMessage("{\"action\":\"ping\"}"));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                messageFuture.complete(message.getPayload());
                session.close();
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                messageFuture.completeExceptionally(exception);
            }
        };

        WebSocketSession session = client.doHandshake(
                handler,
                new WebSocketHttpHeaders(),
                new URI(LLM_WEBSOCKET_URL)
        ).get(TIMEOUT_SECONDS, TimeUnit.SECONDS);

        assertTrue(session.isOpen(), "WebSocket 连接应处于打开状态");

        // 等待响应（可选，某些服务可能不响应 ping）
        try {
            String response = messageFuture.get(TIMEOUT_SECONDS, TimeUnit.SECONDS);
            assertNotNull(response, "应收到 LLM 服务的响应消息");
            System.out.println("LLM 响应: " + response);
        } catch (Exception e) {
            // 如果服务不响应 ping，只要连接成功就算通过
            // 关闭连接
            if (session.isOpen()) {
                session.close();
            }
        }
    }
}
