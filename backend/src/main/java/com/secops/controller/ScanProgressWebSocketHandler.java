package com.secops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描进度 WebSocket 处理器
 * 广播扫描任务进度到所有已连接的客户端
 */
@Slf4j
@Component
public class ScanProgressWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("扫描进度 WebSocket 连接建立: {}", session.getId());
        sessions.add(session);
        sendEvent(session, "connected", Map.of("message", "扫描进度推送已连接"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 前端不需要发送消息，纯推送通道
        log.debug("收到扫描进度通道消息: {}", message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("扫描进度 WebSocket 连接关闭: {}, status={}", session.getId(), status);
        sessions.remove(session);
    }

    /**
     * 广播扫描进度事件到所有连接的客户端
     */
    public void broadcastProgress(String taskId, String status, int progress, String stage, String message) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "SCAN_PROGRESS");
        event.put("taskId", taskId);
        event.put("status", status);
        event.put("progress", progress);
        event.put("stage", stage);
        event.put("message", message);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            log.error("序列化扫描进度事件失败", e);
            return;
        }

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(payload));
                } catch (IOException e) {
                    log.error("向会话 {} 发送扫描进度失败", session.getId(), e);
                }
            }
        }
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
