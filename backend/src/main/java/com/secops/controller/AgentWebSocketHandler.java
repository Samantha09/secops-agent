package com.secops.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secops.agent.core.AgentContext;
import com.secops.agent.core.AgentRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * Agent WebSocket 处理器
 * 接收用户消息，流式推送 Agent 思考过程
 */
@Slf4j
@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentRuntime agentRuntime;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AgentWebSocketHandler(AgentRuntime agentRuntime) {
        this.agentRuntime = agentRuntime;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("Agent WebSocket 连接建立: {}", session.getId());
        sendEvent(session, "connected", Map.of("message", "Agent 连接成功"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String payload = message.getPayload();
        log.info("收到用户消息: {}", payload);

        JsonNode json = objectMapper.readTree(payload);
        String userInput = json.path("content").asText();

        AgentContext context = new AgentContext();
        context.setQuery(userInput);
        context.setSessionId(session.getId());

        agentRuntime.executeStream(context, new AgentRuntime.AgentStreamCallback() {
            @Override
            public void onThink(String thought) {
                sendEvent(session, "think", Map.of("content", thought));
            }

            @Override
            public void onAction(String toolName, String params) {
                sendEvent(session, "action", Map.of("tool", toolName, "params", params));
            }

            @Override
            public void onObserve(String result) {
                sendEvent(session, "observe", Map.of("content", result));
            }

            @Override
            public void onComplete(String finalAnswer) {
                sendEvent(session, "complete", Map.of("content", finalAnswer));
            }

            @Override
            public void onError(String error) {
                sendEvent(session, "error", Map.of("message", error));
            }
        });
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("Agent WebSocket 连接关闭: {}, status={}", session.getId(), status);
    }

    private void sendEvent(WebSocketSession session, String type, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("type", type);
            event.put("data", data);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.error("发送 WebSocket 消息失败", e);
        }
    }
}
