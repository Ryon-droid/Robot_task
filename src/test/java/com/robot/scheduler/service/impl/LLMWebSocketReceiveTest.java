package com.robot.scheduler.service.impl;

import org.junit.jupiter.api.Test;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * LLM WebSocket data receiving test (JUnit version)
 * Keeps the connection for a specified duration and prints all received messages
 */
public class LLMWebSocketReceiveTest {

    private static final String LLM_WEBSOCKET_URL = "ws://172.16.26.123:8090/ws/llm";
    private static final long RECEIVE_DURATION_SECONDS = 60;

    @Test
    public void testReceiveLLMMessages() throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();
        CountDownLatch latch = new CountDownLatch(1);

        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                System.out.println("[CONNECTED] WebSocket session established: " + session.getId());
                System.out.println("[WAITING] Waiting for messages from LLM service... (duration: " + RECEIVE_DURATION_SECONDS + "s)\n");

                // Uncomment below to send a subscription / registration message
                // session.sendMessage(new TextMessage("{\"action\":\"subscribe\",\"topic\":\"plan\"}"));
            }

            @Override
            protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
                String payload = message.getPayload();
                System.out.println("[RECEIVED] Message:");
                System.out.println("--------------------------------------------------");
                System.out.println(payload);
                System.out.println("--------------------------------------------------\n");
            }

            @Override
            public void handleTransportError(WebSocketSession session, Throwable exception) {
                System.err.println("[ERROR] Connection error: " + exception.getMessage());
                latch.countDown();
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
                System.out.println("[CLOSED] Connection closed: " + status);
                latch.countDown();
            }
        };

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        // Set Origin if the server requires it
        // headers.setOrigin("http://172.16.25.152:8090");

        WebSocketSession session = client.doHandshake(
                handler,
                headers,
                new URI(LLM_WEBSOCKET_URL)
        ).get(5, TimeUnit.SECONDS);

        // Block for the specified duration to keep receiving messages
        latch.await(RECEIVE_DURATION_SECONDS, TimeUnit.SECONDS);

        if (session.isOpen()) {
            session.close();
        }
        System.out.println("[DONE] Test finished");
    }
}