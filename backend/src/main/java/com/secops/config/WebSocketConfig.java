package com.secops.config;

import com.secops.controller.AgentWebSocketHandler;
import com.secops.controller.ScanProgressWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ScanProgressWebSocketHandler scanProgressWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler,
                           ScanProgressWebSocketHandler scanProgressWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.scanProgressWebSocketHandler = scanProgressWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .setAllowedOrigins("*");
        registry.addHandler(scanProgressWebSocketHandler, "/ws/scans")
                .setAllowedOrigins("*");
    }
}
