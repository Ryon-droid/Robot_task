package com.robot.scheduler.config;

import com.robot.scheduler.service.impl.VisionWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

/**
 * WebSocket 服务端配置
 * <p>
 * 注册视觉识别客户端连接的端点：/ws/vision
 */
@Configuration
@EnableWebSocket
public class WebSocketServerConfig implements WebSocketConfigurer {

    @Autowired
    private VisionWebSocketHandler visionWebSocketHandler;

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(visionWebSocketHandler, "/ws/vision")
                .setAllowedOrigins("*");
    }
}
