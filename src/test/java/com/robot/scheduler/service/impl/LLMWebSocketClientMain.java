package com.robot.scheduler.service.impl;

import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.net.URI;
import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/**
 * LLM WebSocket client standalone program
 * Connects to the LLM service, displays received messages in real time.
 * Press Enter to exit.
 *
 * Run methods:
 * 1. Run main() directly in IDE
 * 2. Or execute in project root:
 *    mvn -q exec:java -Dexec.mainClass=com.robot.scheduler.service.impl.LLMWebSocketClientMain -Dexec.classpathScope=test
 */
public class LLMWebSocketClientMain {

    private static final String LLM_WEBSOCKET_URL = "ws://172.16.25.192:8090/ws/llm";

    public static void main(String[] args) throws Exception {
        StandardWebSocketClient client = new StandardWebSocketClient();

        TextWebSocketHandler handler = new TextWebSocketHandler() {
            @Override
            public void afterConnectionEstablished(WebSocketSession session) throws Exception {
                System.out.println("[CONNECTED] WebSocket session established: " + session.getId());
                System.out.println("[WAITING] Waiting for messages from LLM service...\n");

                // Uncomment below to send an initial message
                // session.sendMessage(new TextMessage("{\"action\":\"subscribe\"}"));
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
            }

            @Override
            public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
                System.out.println("[CLOSED] Connection closed: " + status);
            }
        };

        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        // headers.setOrigin("http://172.16.25.192:8090");

        WebSocketSession session = client.doHandshake(
                handler,
                headers,
                new URI(LLM_WEBSOCKET_URL)
        ).get(5, TimeUnit.SECONDS);

        System.out.println("Press Enter to stop receiving...\n");
        new Scanner(System.in).nextLine();

        if (session.isOpen()) {
            session.close();
        }
        System.out.println("[DONE] Program exited");
    }
}
