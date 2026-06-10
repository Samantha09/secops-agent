package com.secops.service;

import com.secops.agent.core.AgentContext;
import com.secops.agent.core.AgentRuntime;
import com.secops.controller.ScanProgressWebSocketHandler;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.VulnerabilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Agent 扫描结果分析服务
 * 扫描完成后自动触发，分析漏洞并生成修复建议，对高危漏洞创建 Ticket
 */
@Slf4j
@Service
public class AgentScanAnalysisService {

    private final AgentRuntime agentRuntime;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final TicketService ticketService;
    private final ScanProgressWebSocketHandler webSocketHandler;

    public AgentScanAnalysisService(AgentRuntime agentRuntime,
                                    VulnerabilityRepository vulnerabilityRepository,
                                    TicketService ticketService,
                                    ScanProgressWebSocketHandler webSocketHandler) {
        this.agentRuntime = agentRuntime;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.ticketService = ticketService;
        this.webSocketHandler = webSocketHandler;
    }

    @Async
    public void analyzeScanTask(ScanTask task) {
        try {
            broadcast(task, "AGENT_ANALYZING", "Agent 正在分析扫描结果...", 0);

            List<Vulnerability> vulns = vulnerabilityRepository.findByScanTaskId(task.getId());
            if (vulns.isEmpty()) {
                broadcast(task, "AGENT_COMPLETE", "未发现漏洞，无需分析", 100);
                return;
            }

            String query = buildAnalysisQuery(task, vulns);
            AgentContext context = new AgentContext();
            context.setQuery(query);
            context.setSessionId("scan-" + task.getTaskId());

            StringBuilder analysisResult = new StringBuilder();
            agentRuntime.executeStream(context, new AgentRuntime.AgentStreamCallback() {
                @Override
                public void onThink(String thought) {
                    broadcast(task, "AGENT_THINKING", thought, 50);
                }

                @Override
                public void onAction(String toolName, String params) {}

                @Override
                public void onObserve(String result) {}

                @Override
                public void onComplete(String finalAnswer) {
                    analysisResult.append(finalAnswer);
                }

                @Override
                public void onError(String error) {
                    log.error("Agent 分析失败: {}", error);
                    broadcast(task, "AGENT_ERROR", "分析失败: " + error, 100);
                }
            });

            // 为 CRITICAL/HIGH 漏洞自动创建 Ticket
            int ticketCount = 0;
            for (Vulnerability vuln : vulns) {
                if (vuln.getSeverity() == Severity.CRITICAL || vuln.getSeverity() == Severity.HIGH) {
                    if (vuln.getStatus() == VulnStatus.OPEN || vuln.getStatus() == VulnStatus.REOPENED) {
                        try {
                            ticketService.createAutoTicket(vuln.getId(), analysisResult.toString());
                            ticketCount++;
                        } catch (Exception e) {
                            log.warn("自动创建工单失败: {}", e.getMessage());
                        }
                    }
                }
            }

            broadcast(task, "AGENT_COMPLETE",
                    String.format("分析完成，已为 %d 个高危漏洞创建修复工单", ticketCount), 100);

        } catch (Exception e) {
            log.error("Agent 扫描分析异常", e);
            broadcast(task, "AGENT_ERROR", "分析异常: " + e.getMessage(), 100);
        }
    }

    private String buildAnalysisQuery(ScanTask task, List<Vulnerability> vulns) {
        StringBuilder sb = new StringBuilder();
        sb.append("请分析以下扫描结果并给出修复建议：\n\n");
        sb.append("扫描目标: ").append(task.getTarget().getDomain()).append("\n");
        sb.append("发现漏洞数量: ").append(vulns.size()).append("\n\n");
        sb.append("漏洞列表:\n");
        for (Vulnerability v : vulns) {
            sb.append("- [").append(v.getSeverity()).append("] ")
              .append(v.getName()).append("\n")
              .append("  描述: ").append(v.getDescription()).append("\n")
              .append("  位置: ").append(v.getMatched()).append("\n\n");
        }
        sb.append("请为每个漏洞提供：\n");
        sb.append("1. 根因分析\n");
        sb.append("2. 具体修复步骤（含代码/配置示例）\n");
        sb.append("3. 验证修复是否成功的方法\n");
        return sb.toString();
    }

    private void broadcast(ScanTask task, String stage, String message, int progress) {
        webSocketHandler.broadcastProgress(
                task.getTaskId(),
                task.getStatus().name(),
                progress,
                stage,
                message
        );
    }
}
