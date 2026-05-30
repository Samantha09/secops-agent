package com.secops.agent.core;

import lombok.Data;
import java.util.List;
import java.util.Map;

/**
 * Agent 运行时上下文
 * 包含用户问题、可用工具、历史记忆等
 */
@Data
public class AgentContext {
    private String sessionId;
    private String userId;
    private String query;
    private List<ToolCall> availableTools;
    private List<Message> history;
    private Map<String, Object> memory;

    @Data
    public static class ToolCall {
        private String name;
        private Map<String, Object> parameters;
        private String result;
    }

    @Data
    public static class Message {
        private String role; // user / assistant / system / tool
        private String content;
    }
}
