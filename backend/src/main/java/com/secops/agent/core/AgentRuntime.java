package com.secops.agent.core;

/**
 * Agent 运行时接口
 * ReAct 循环：思考(Thought) -> 行动(Action) -> 观察(Observation)
 */
public interface AgentRuntime {

    /**
     * 执行单轮 Agent 推理
     * @param context 上下文（包含用户输入和可用工具）
     * @return Agent 回复
     */
    AgentResponse execute(AgentContext context);

    /**
     * 流式执行（WebSocket 实时推送思考过程）
     */
    void executeStream(AgentContext context, AgentStreamCallback callback);

    interface AgentStreamCallback {
        void onThink(String thought);
        void onAction(String toolName, String params);
        void onObserve(String result);
        void onComplete(String finalAnswer);
        void onError(String error);
    }

    @Data
    class AgentResponse {
        private String finalAnswer;
        private List<AgentContext.ToolCall> toolCalls;
        private boolean needsHumanConfirm;
        private String confirmReason;
    }
}
