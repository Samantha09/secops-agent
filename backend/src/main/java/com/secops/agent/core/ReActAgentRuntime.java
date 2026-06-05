package com.secops.agent.core;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

/**
 * ReAct Agent 运行时简化实现
 * 如果未配置 API Key，则返回模拟回复
 */
@Slf4j
@Component
public class ReActAgentRuntime implements AgentRuntime {

    @Value("${agent.llm.api-key:}")
    private String apiKey;

    @Value("${agent.llm.base-url:https://api.openai.com/v1}")
    private String baseUrl;

    @Value("${agent.llm.model:gpt-4o-mini}")
    private String model;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public AgentResponse execute(AgentContext context) {
        StringBuilder result = new StringBuilder();
        executeStream(context, new AgentStreamCallback() {
            @Override
            public void onThink(String thought) {}
            @Override
            public void onAction(String toolName, String params) {}
            @Override
            public void onObserve(String result) {}
            @Override
            public void onComplete(String finalAnswer) {
                result.append(finalAnswer);
            }
            @Override
            public void onError(String error) {
                result.append("Error: ").append(error);
            }
        });
        AgentResponse response = new AgentResponse();
        response.setFinalAnswer(result.toString());
        return response;
    }

    @Override
    public void executeStream(AgentContext context, AgentStreamCallback callback) {
        if (apiKey == null || apiKey.isBlank()) {
            simulateResponse(context, callback);
            return;
        }
        callLlmStream(context, callback);
    }

    private void simulateResponse(AgentContext context, AgentStreamCallback callback) {
        String userInput = context.getQuery();
        callback.onThink("正在分析用户问题...");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        callback.onThink("用户询问的是关于安全漏洞的问题，我需要检索相关知识库...");
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}

        callback.onAction("knowledge_search", "{\"query\": \"" + userInput + "\"}");
        try { Thread.sleep(300); } catch (InterruptedException ignored) {}

        callback.onObserve("检索到相关漏洞信息：Spring Boot Actuator 未授权访问是常见高危漏洞。");
        try { Thread.sleep(400); } catch (InterruptedException ignored) {}

        String answer = "【模拟回复】\n\n您好！由于当前未配置 LLM API Key，Agent 正在以模拟模式运行。\n\n您的问题是：\"" + userInput + "\"\n\n在实际环境中，Agent 会调用 LLM 进行深度分析，并结合扫描结果知识库为您提供：\n1. 漏洞根因分析\n2. 具体修复步骤\n3. 验证修复是否成功的方法\n\n如需启用真实 Agent 能力，请在环境变量中设置 OPENAI_API_KEY。";
        callback.onComplete(answer);
    }

    private void callLlmStream(AgentContext context, AgentStreamCallback callback) {
        try {
            URL url = new URL(baseUrl + "/chat/completions");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setRequestProperty("Authorization", "Bearer " + apiKey);
            conn.setRequestProperty("Content-Type", "application/json");
            conn.setDoOutput(true);

            List<Message> messages = new ArrayList<>();
            messages.add(new Message("system", "You are SecOps Agent, an intelligent security operations assistant. You help users analyze vulnerability scan results, provide remediation advice, and answer security questions. Think step by step."));
            messages.add(new Message("user", context.getQuery()));

            RequestBody body = new RequestBody(model, messages, true);
            String jsonBody = objectMapper.writeValueAsString(body);

            try (OutputStreamWriter writer = new OutputStreamWriter(conn.getOutputStream(), StandardCharsets.UTF_8)) {
                writer.write(jsonBody);
            }

            try (Scanner scanner = new Scanner(conn.getInputStream(), StandardCharsets.UTF_8)) {
                while (scanner.hasNextLine()) {
                    String line = scanner.nextLine();
                    if (line.startsWith("data: ")) {
                        String data = line.substring(6);
                        if ("[DONE]".equals(data)) break;
                        try {
                            JsonNode root = objectMapper.readTree(data);
                            JsonNode choices = root.path("choices");
                            if (choices.isArray() && !choices.isEmpty()) {
                                JsonNode delta = choices.get(0).path("delta");
                                String content = delta.path("content").asText();
                                if (!content.isEmpty()) {
                                    callback.onThink(content);
                                }
                            }
                        } catch (Exception e) {
                            log.warn("解析 SSE 数据失败: {}", e.getMessage());
                        }
                    }
                }
            }
            callback.onComplete("");
        } catch (Exception e) {
            log.error("LLM 调用失败", e);
            callback.onError(e.getMessage());
        }
    }

    private record Message(String role, String content) {}
    private record RequestBody(String model, List<Message> messages, boolean stream) {}
}
