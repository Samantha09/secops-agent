# 扫描引擎层改造（第二批）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现扫描结果去重与关联（复现检测、REOPENED 状态），以及扫描完成后 Agent 自动分析生成修复建议并创建 Ticket。

**Architecture:** 在 `ScannerEngineService` 漏洞入库阶段增加去重逻辑；新增 `AgentScanAnalysisService` 封装 Agent 分析扫描结果的能力；扫描完成时异步触发分析流程，通过 WebSocket 推送进度。

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Data JPA, Spring WebSocket, React 19, Ant Design

---

## 文件结构映射

| 文件 | 操作 | 职责 |
|------|------|------|
| `entity/enums/VulnStatus.java` | 修改 | 新增 REOPENED 状态 |
| `entity/Vulnerability.java` | 修改 | 新增 firstFoundAt、lastFoundAt、reopenCount |
| `repository/VulnerabilityRepository.java` | 修改 | 新增去重查询方法 |
| `service/ScannerEngineService.java` | 修改 | 入库前去重逻辑 |
| `service/AgentScanAnalysisService.java` | 创建 | Agent 分析扫描结果、生成修复建议、创建 Ticket |
| `service/TicketService.java` | 修改 | 新增自动创建 Ticket 方法 |
| `controller/VulnerabilityController.java` | 修改 | 新增状态更新接口支持 REOPENED |
| `pages/Vulnerabilities.jsx` | 修改 | 显示复现次数、首次/最近发现时间 |
| `pages/ScanTasks.jsx` | 修改 | 扫描完成后显示 Agent 分析状态 |

---

### Task 1: Vulnerability 实体增强 + VulnStatus 扩展

**Files:**
- Modify: `backend/src/main/java/com/secops/entity/enums/VulnStatus.java`
- Modify: `backend/src/main/java/com/secops/entity/Vulnerability.java`

- [ ] **Step 1: 扩展 VulnStatus 枚举**

```java
package com.secops.entity.enums;

public enum VulnStatus {
    OPEN, FIXED, FALSE_POSITIVE, REOPENED
}
```

- [ ] **Step 2: 增强 Vulnerability 实体**

在 `Vulnerability.java` 中新增以下字段（保留现有字段不变）：

```java
@Column(nullable = false)
private LocalDateTime firstFoundAt = LocalDateTime.now();

private LocalDateTime lastFoundAt = LocalDateTime.now();

@Column(nullable = false)
private int reopenCount = 0;
```

并添加 `@PreUpdate` 方法，在更新时自动刷新 `lastFoundAt`：

```java
@PreUpdate
public void preUpdate() {
    this.lastFoundAt = LocalDateTime.now();
}
```

完整实体代码：

```java
package com.secops.entity;

import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "vulnerabilities")
@Data
public class Vulnerability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(length = 2000)
    private String description;

    private String matched;

    private String target;

    private String scanner;

    private LocalDateTime foundAt = LocalDateTime.now();

    @Column(nullable = false)
    private LocalDateTime firstFoundAt = LocalDateTime.now();

    private LocalDateTime lastFoundAt = LocalDateTime.now();

    @Column(nullable = false)
    private int reopenCount = 0;

    @Enumerated(EnumType.STRING)
    private VulnStatus status = VulnStatus.OPEN;

    @ManyToOne
    @JoinColumn(name = "scan_task_id", nullable = false)
    private ScanTask scanTask;

    @PreUpdate
    public void preUpdate() {
        this.lastFoundAt = LocalDateTime.now();
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/entity/enums/VulnStatus.java backend/src/main/java/com/secops/entity/Vulnerability.java
git commit -m "feat(vuln): 扩展漏洞实体，新增复现追踪字段和 REOPENED 状态"
```

---

### Task 2: VulnerabilityRepository 去重查询

**Files:**
- Modify: `backend/src/main/java/com/secops/repository/VulnerabilityRepository.java`

- [ ] **Step 1: 添加去重查询方法**

```java
package com.secops.repository;

import com.secops.entity.Vulnerability;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface VulnerabilityRepository extends JpaRepository<Vulnerability, Long> {
    List<Vulnerability> findByScanTaskId(Long scanTaskId);
    List<Vulnerability> findByStatus(VulnStatus status);
    List<Vulnerability> findBySeverity(Severity severity);
    List<Vulnerability> findTop5ByOrderByFoundAtDesc();
    long countByStatus(VulnStatus status);
    long countBySeverity(Severity severity);

    /**
     * 按目标、漏洞名、匹配位置查询（用于去重）
     */
    Optional<Vulnerability> findByTargetAndNameAndMatched(String target, String name, String matched);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/repository/VulnerabilityRepository.java
git commit -m "feat(vuln): VulnerabilityRepository 增加去重查询方法"
```

---

### Task 3: ScannerEngineService 去重入库逻辑

**Files:**
- Modify: `backend/src/main/java/com/secops/service/ScannerEngineService.java`

- [ ] **Step 1: 修改漏洞保存方法**

在 `ScannerEngineService` 中，将原先直接 `vulnerabilityRepository.save(v)` 的地方替换为调用新的 `saveOrUpdateVulnerability` 方法。

找到两处保存漏洞的代码（Nuclei 结果和降级探测结果），将：

```java
Vulnerability v = new Vulnerability();
v.setName(finding.getName());
// ... 其他字段设置 ...
vulnerabilityRepository.save(v);
```

替换为：

```java
saveOrUpdateVulnerability(finding, task);
```

- [ ] **Step 2: 新增去重保存方法**

在 `ScannerEngineService` 中添加：

```java
private void saveOrUpdateVulnerability(ScanResult.Finding finding, ScanTask task) {
    String target = task.getTarget().getDomain();
    Optional<Vulnerability> existing = vulnerabilityRepository
            .findByTargetAndNameAndMatched(target, finding.getName(), finding.getMatched());

    if (existing.isPresent()) {
        Vulnerability v = existing.get();
        v.setLastFoundAt(LocalDateTime.now());
        v.setScanTask(task);

        if (v.getStatus() == VulnStatus.FIXED) {
            v.setStatus(VulnStatus.REOPENED);
            v.setReopenCount(v.getReopenCount() + 1);
            pushProgress(task, "VULN_SCAN", "漏洞复现: " + finding.getName() + " (REOPENED)", 90);
        } else {
            pushProgress(task, "VULN_SCAN", "漏洞复现: " + finding.getName(), 90);
        }
        vulnerabilityRepository.save(v);
    } else {
        Vulnerability v = new Vulnerability();
        v.setName(finding.getName());
        v.setSeverity(parseSeverity(finding.getSeverity()));
        v.setDescription(finding.getDescription());
        v.setMatched(finding.getMatched());
        v.setTarget(target);
        v.setScanner("nuclei");
        v.setScanTask(task);
        v.setFirstFoundAt(LocalDateTime.now());
        v.setLastFoundAt(LocalDateTime.now());
        v.setReopenCount(0);
        v.setStatus(VulnStatus.OPEN);
        vulnerabilityRepository.save(v);
    }
}
```

注意：降级探测（http-probe）保存时 `scanner` 字段应设为 `"http-probe"`，可以重载一个方法或传入 scanner 名称参数。

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/service/ScannerEngineService.java
git commit -m "feat(vuln): ScannerEngineService 增加漏洞去重入库逻辑"
```

---

### Task 4: 前端漏洞列表显示复现信息

**Files:**
- Modify: `frontend/src/pages/Vulnerabilities.jsx`

- [ ] **Step 1: 修改漏洞列表页面**

在表格列中增加：
- `firstFoundAt` → "首次发现"
- `lastFoundAt` → "最近发现"
- `reopenCount` → "复现次数"

状态标签增加 REOPENED 显示（红色）：

```javascript
const statusMap = {
  'OPEN': { text: '未修复', color: 'red' },
  'FIXED': { text: '已修复', color: 'green' },
  'FALSE_POSITIVE': { text: '误报', color: 'default' },
  'REOPENED': { text: '复现', color: 'volcano' },
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/Vulnerabilities.jsx
git commit -m "feat(vuln): 前端漏洞列表显示复现次数和首次/最近发现时间"
```

---

### Task 5: Agent 扫描结果分析 Service

**Files:**
- Create: `backend/src/main/java/com/secops/service/AgentScanAnalysisService.java`

- [ ] **Step 1: 创建 AgentScanAnalysisService**

```java
package com.secops.service;

import com.secops.agent.core.AgentContext;
import com.secops.agent.core.AgentRuntime;
import com.secops.controller.ScanProgressWebSocketHandler;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.Severity;
import com.secops.repository.VulnerabilityRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

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
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/service/AgentScanAnalysisService.java
git commit -m "feat(agent): 新增 AgentScanAnalysisService 扫描结果自动分析"
```

---

### Task 6: TicketService 自动创建 Ticket 方法

**Files:**
- Modify: `backend/src/main/java/com/secops/service/TicketService.java`

- [ ] **Step 1: 新增自动创建 Ticket 方法**

```java
public Ticket createAutoTicket(Long vulnerabilityId, String remediationAdvice) {
    Vulnerability vuln = vulnerabilityRepository.findById(vulnerabilityId)
            .orElseThrow(() -> new IllegalArgumentException("漏洞不存在"));

    // 检查是否已存在该漏洞的 OPEN 工单
    List<Ticket> existing = ticketRepository.findByVulnerabilityId(vulnerabilityId);
    boolean hasOpenTicket = existing.stream()
            .anyMatch(t -> t.getStatus() != TicketStatus.CLOSED);
    if (hasOpenTicket) {
        log.info("漏洞 {} 已存在未关闭工单，跳过自动创建", vulnerabilityId);
        return null;
    }

    Ticket ticket = new Ticket();
    ticket.setTitle("[Auto] " + vuln.getName());
    ticket.setPriority(vuln.getSeverity());
    ticket.setVulnerability(vuln);
    ticket.setStatus(TicketStatus.OPEN);
    // remediationAdvice 可以存入 Ticket 的备注字段（如果实体有备注字段）
    // 如 Ticket 实体无备注字段，可暂不存储或后续扩展
    return ticketRepository.save(ticket);
}
```

同时需要确保 `TicketRepository` 有 `findByVulnerabilityId` 方法：

```java
List<Ticket> findByVulnerabilityId(Long vulnerabilityId);
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/service/TicketService.java backend/src/main/java/com/secops/repository/TicketRepository.java
git commit -m "feat(ticket): TicketService 支持自动创建工单方法"
```

---

### Task 7: 扫描完成后自动触发 Agent 分析

**Files:**
- Modify: `backend/src/main/java/com/secops/service/ScannerEngineService.java`

- [ ] **Step 1: 注入 AgentScanAnalysisService**

在 `ScannerEngineService` 构造函数中增加 `AgentScanAnalysisService` 注入。

- [ ] **Step 2: 在扫描完成时触发分析**

在 `completeTask` 方法末尾添加：

```java
private void completeTask(ScanTask task, String rawOutput) {
    task.setStatus(ScanStatus.COMPLETED);
    task.setProgress(100);
    task.setEndTime(LocalDateTime.now());
    task.setRawOutput(rawOutput);
    scanTaskRepository.save(task);
    pushProgress(task, "COMPLETED", "扫描完成", 100);

    // 触发 Agent 自动分析
    agentScanAnalysisService.analyzeScanTask(task);
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/service/ScannerEngineService.java
git commit -m "feat(agent): 扫描完成后自动触发 Agent 分析并创建修复工单"
```

---

### Task 8: 前端扫描任务显示 Agent 分析状态

**Files:**
- Modify: `frontend/src/pages/ScanTasks.jsx`

- [ ] **Step 1: 扩展 stageMap 和状态显示**

在前端 `stageMap` 中增加 Agent 分析阶段：

```javascript
const stageMap = {
  'SUBDOMAIN_SCAN': '子域名发现',
  'PORT_SCAN': '端口扫描',
  'HTTP_PROBE': '存活探测',
  'VULN_SCAN': '漏洞扫描',
  'COMPLETED': '扫描完成',
  'FAILED': '执行失败',
  'AGENT_ANALYZING': 'Agent 分析中',
  'AGENT_THINKING': 'Agent 思考中',
  'AGENT_COMPLETE': '分析完成',
  'AGENT_ERROR': '分析失败',
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/ScanTasks.jsx
git commit -m "feat(agent): 前端扫描任务页面显示 Agent 分析阶段"
```

---

### Task 9: 集成验证

**Files:**
- 无新增文件

- [ ] **Step 1: 后端编译与测试**

```bash
cd backend
mvn clean test -q
```

Expected: `BUILD SUCCESS`

- [ ] **Step 2: 前端编译检查**

```bash
cd frontend
npm run build
```

Expected: 无编译错误。

- [ ] **Step 3: Commit（如有修复）**

```bash
git add -A
git commit -m "fix(scanner): 第二批集成验证修复"
```

---

## Self-Review

**1. Spec coverage:**
- ✅ 结果去重 — Task 1-3（实体扩展、Repository 查询、Service 去重逻辑）
- ✅ 复现检测 — Task 3（FIXED → REOPENED 状态流转）
- ✅ Agent 自动分析 — Task 5-7（AgentScanAnalysisService、扫描完成触发）
- ✅ 自动创建 Ticket — Task 5-6（CRITICAL/HIGH 漏洞自动创建工单）
- ✅ WebSocket 推送 — Task 5（分析过程实时推送）
- ✅ 前端显示 — Task 4, 8（漏洞复现信息、Agent 分析阶段）

**2. Placeholder scan:**
- ✅ 无 TBD/TODO
- ✅ 每个步骤都有完整代码

**3. Type consistency:**
- ✅ `VulnStatus` 枚举新增 REOPENED，前后端同步更新
- ✅ WebSocket 消息字段与第一批保持一致
- ✅ `Severity` / `TicketStatus` 使用现有枚举值
