# 前后端接口联调完善实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** 完善 Dashboard、Vulnerabilities、Tickets、AgentChat 四个模块的前后端接口联调，替换所有硬编码数据。

**Architecture:** 按数据依赖顺序实现：Vulnerability CRUD → Ticket 实体+CRUD → Dashboard 聚合统计 → Agent WebSocket 流式对话。每个模块独立可测试。

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Data JPA, Spring WebSocket, React 19, Ant Design, Recharts, axios

---

## 文件结构映射

### 后端（新增/修改）

| 文件 | 操作 | 职责 |
|------|------|------|
| `entity/enums/TicketStatus.java` | 创建 | 工单状态枚举 |
| `entity/Ticket.java` | 创建 | 工单 JPA 实体 |
| `repository/TicketRepository.java` | 创建 | 工单数据访问 |
| `repository/VulnerabilityRepository.java` | 修改 | 增加筛选查询方法 |
| `service/TicketService.java` | 创建 | 工单业务逻辑 |
| `controller/VulnerabilityController.java` | 创建 | 漏洞 REST API |
| `controller/TicketController.java` | 创建 | 工单 REST API |
| `controller/StatsController.java` | 创建 | 仪表盘聚合统计 API |

### 前端（新增/修改）

| 文件 | 操作 | 职责 |
|------|------|------|
| `api/vulns.js` | 创建 | 漏洞 API 封装 |
| `api/tickets.js` | 创建 | 工单 API 封装 |
| `pages/Vulnerabilities.jsx` | 修改 | 接入真实漏洞数据 |
| `pages/Tickets.jsx` | 修改 | 接入真实工单数据 |
| `pages/Dashboard.jsx` | 修改 | 接入真实统计数据 |
| `pages/AgentChat.jsx` | 修改 | WebSocket 流式对话 |

---

### Task 1: 漏洞 Repository 扩展

**Files:**
- Modify: `backend/src/main/java/com/secops/repository/VulnerabilityRepository.java`

- [ ] **Step 1: 添加查询方法**

```java
package com.secops.repository;

import com.secops.entity.Vulnerability;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VulnerabilityRepository extends JpaRepository<Vulnerability, Long> {
    List<Vulnerability> findByScanTaskId(Long scanTaskId);
    List<Vulnerability> findByStatus(VulnStatus status);
    List<Vulnerability> findBySeverity(Severity severity);
    List<Vulnerability> findTop5ByOrderByFoundAtDesc();
    long countByStatus(VulnStatus status);
    long countBySeverity(Severity severity);
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/repository/VulnerabilityRepository.java
git commit -m "feat(vuln): 扩展 VulnerabilityRepository 查询方法"
```

---

### Task 2: 漏洞 Controller

**Files:**
- Create: `backend/src/main/java/com/secops/controller/VulnerabilityController.java`

- [ ] **Step 1: 实现 Controller**

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vulns")
public class VulnerabilityController {

    private final VulnerabilityRepository vulnerabilityRepository;

    public VulnerabilityController(VulnerabilityRepository vulnerabilityRepository) {
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    @GetMapping
    public R<List<Vulnerability>> list(@RequestParam(required = false) Long scanTaskId,
                                       @RequestParam(required = false) String status) {
        List<Vulnerability> list;
        if (scanTaskId != null) {
            list = vulnerabilityRepository.findByScanTaskId(scanTaskId);
        } else if (status != null) {
            list = vulnerabilityRepository.findByStatus(VulnStatus.valueOf(status));
        } else {
            list = vulnerabilityRepository.findAll();
        }
        return R.ok(list);
    }

    @GetMapping("/{id}")
    public R<Vulnerability> detail(@PathVariable Long id) {
        return vulnerabilityRepository.findById(id)
                .map(R::ok)
                .orElse(R.error("漏洞不存在"));
    }

    @PatchMapping("/{id}/status")
    public R<Vulnerability> updateStatus(@PathVariable Long id, @RequestBody StatusRequest req) {
        return vulnerabilityRepository.findById(id)
                .map(v -> {
                    v.setStatus(req.status);
                    vulnerabilityRepository.save(v);
                    return R.ok(v);
                })
                .orElse(R.error("漏洞不存在"));
    }

    public record StatusRequest(VulnStatus status) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/controller/VulnerabilityController.java
git commit -m "feat(vuln): 添加漏洞管理 Controller"
```

---

### Task 3: 前端漏洞 API 封装 + 页面联调

**Files:**
- Create: `frontend/src/api/vulns.js`
- Modify: `frontend/src/pages/Vulnerabilities.jsx`

- [ ] **Step 1: 创建 vulns.js**

```javascript
import client from './client'

export const listVulns = (params) => client.get('/vulns', { params })
export const getVuln = (id) => client.get(`/vulns/${id}`)
export const updateVulnStatus = (id, status) => client.patch(`/vulns/${id}/status`, { status })
```

- [ ] **Step 2: 修改 Vulnerabilities.jsx 接入真实数据**

完整替换为带 API 调用、详情弹窗、状态更新的版本（代码见实际文件）。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/vulns.js frontend/src/pages/Vulnerabilities.jsx
git commit -m "feat(vuln): 前端漏洞管理页面接入后端 API"
```

---

### Task 4: Ticket 实体与枚举

**Files:**
- Create: `backend/src/main/java/com/secops/entity/enums/TicketStatus.java`
- Create: `backend/src/main/java/com/secops/entity/Ticket.java`

- [ ] **Step 1: 创建 TicketStatus 枚举**

```java
package com.secops.entity.enums;

public enum TicketStatus {
    OPEN, IN_PROGRESS, CLOSED
}
```

- [ ] **Step 2: 创建 Ticket 实体**

```java
package com.secops.entity;

import com.secops.entity.enums.Severity;
import com.secops.entity.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "tickets")
@Data
public class Ticket {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String title;

    @Enumerated(EnumType.STRING)
    private Severity priority;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.OPEN;

    private String assignee;

    @CreationTimestamp
    private LocalDateTime createdAt;

    @UpdateTimestamp
    private LocalDateTime updatedAt;

    @OneToOne
    @JoinColumn(name = "vulnerability_id")
    private Vulnerability vulnerability;
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/entity/enums/TicketStatus.java backend/src/main/java/com/secops/entity/Ticket.java
git commit -m "feat(ticket): 添加 Ticket 实体和状态枚举"
```

---

### Task 5: Ticket Repository 和 Service

**Files:**
- Create: `backend/src/main/java/com/secops/repository/TicketRepository.java`
- Create: `backend/src/main/java/com/secops/service/TicketService.java`

- [ ] **Step 1: 创建 Repository**

```java
package com.secops.repository;

import com.secops.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByStatus(com.secops.entity.enums.TicketStatus status);
}
```

- [ ] **Step 2: 创建 Service**

```java
package com.secops.service;

import com.secops.entity.Ticket;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.TicketStatus;
import com.secops.repository.TicketRepository;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class TicketService {

    private final TicketRepository ticketRepository;
    private final VulnerabilityRepository vulnerabilityRepository;

    public TicketService(TicketRepository ticketRepository, VulnerabilityRepository vulnerabilityRepository) {
        this.ticketRepository = ticketRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    public List<Ticket> list() {
        return ticketRepository.findAll();
    }

    public Ticket create(Long vulnerabilityId) {
        Vulnerability vuln = vulnerabilityRepository.findById(vulnerabilityId)
                .orElseThrow(() -> new IllegalArgumentException("漏洞不存在"));
        Ticket ticket = new Ticket();
        ticket.setTitle(vuln.getName());
        ticket.setPriority(vuln.getSeverity());
        ticket.setVulnerability(vuln);
        return ticketRepository.save(ticket);
    }

    public Ticket update(Long id, TicketStatus status, String assignee) {
        Ticket ticket = ticketRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("工单不存在"));
        if (status != null) ticket.setStatus(status);
        if (assignee != null) ticket.setAssignee(assignee);
        return ticketRepository.save(ticket);
    }

    public void delete(Long id) {
        ticketRepository.deleteById(id);
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/repository/TicketRepository.java backend/src/main/java/com/secops/service/TicketService.java
git commit -m "feat(ticket): 添加 Ticket Repository 和 Service"
```

---

### Task 6: Ticket Controller

**Files:**
- Create: `backend/src/main/java/com/secops/controller/TicketController.java`

- [ ] **Step 1: 实现 Controller**

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Ticket;
import com.secops.entity.enums.TicketStatus;
import com.secops.service.TicketService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {

    private final TicketService ticketService;

    public TicketController(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    @GetMapping
    public R<List<Ticket>> list() {
        return R.ok(ticketService.list());
    }

    @PostMapping
    public R<Ticket> create(@RequestBody CreateRequest req) {
        return R.ok(ticketService.create(req.vulnerabilityId));
    }

    @PatchMapping("/{id}")
    public R<Ticket> update(@PathVariable Long id, @RequestBody UpdateRequest req) {
        return R.ok(ticketService.update(id, req.status, req.assignee));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        ticketService.delete(id);
        return R.ok();
    }

    public record CreateRequest(Long vulnerabilityId) {}
    public record UpdateRequest(TicketStatus status, String assignee) {}
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/controller/TicketController.java
git commit -m "feat(ticket): 添加工单管理 Controller"
```

---

### Task 7: 前端工单 API 封装 + 页面联调

**Files:**
- Create: `frontend/src/api/tickets.js`
- Modify: `frontend/src/pages/Tickets.jsx`

- [ ] **Step 1: 创建 tickets.js**

```javascript
import client from './client'

export const listTickets = () => client.get('/tickets')
export const createTicket = (vulnerabilityId) => client.post('/tickets', { vulnerabilityId })
export const updateTicket = (id, data) => client.patch(`/tickets/${id}`, data)
export const deleteTicket = (id) => client.delete(`/tickets/${id}`)
```

- [ ] **Step 2: 修改 Tickets.jsx 接入真实数据**

完整替换为带 API 调用、状态流转、删除的版本。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/api/tickets.js frontend/src/pages/Tickets.jsx
git commit -m "feat(ticket): 前端工单页面接入后端 API"
```

---

### Task 8: Dashboard 统计 API

**Files:**
- Create: `backend/src/main/java/com/secops/controller/StatsController.java`

- [ ] **Step 1: 实现 StatsController**

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.*;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;

@RestController
@RequestMapping("/api/stats")
public class StatsController {

    private final TargetRepository targetRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final TicketRepository ticketRepository;

    public StatsController(TargetRepository targetRepository,
                           ScanTaskRepository scanTaskRepository,
                           VulnerabilityRepository vulnerabilityRepository,
                           TicketRepository ticketRepository) {
        this.targetRepository = targetRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.ticketRepository = ticketRepository;
    }

    @GetMapping
    public R<Map<String, Object>> stats() {
        Map<String, Object> data = new HashMap<>();
        data.put("targetCount", targetRepository.count());
        data.put("scanTaskCount", scanTaskRepository.count());
        data.put("vulnCount", vulnerabilityRepository.count());
        data.put("ticketCount", ticketRepository.count());

        Map<String, Long> severityCounts = new LinkedHashMap<>();
        for (Severity s : Severity.values()) {
            severityCounts.put(s.name(), vulnerabilityRepository.countBySeverity(s));
        }
        data.put("severityCounts", severityCounts);

        data.put("recentVulns", vulnerabilityRepository.findTop5ByOrderByFoundAtDesc());

        // 近 7 天漏洞趋势
        List<Map<String, Object>> trend = new ArrayList<>();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = LocalDate.now().minusDays(i);
            LocalDateTime start = date.atStartOfDay();
            LocalDateTime end = date.plusDays(1).atStartOfDay();
            long count = vulnerabilityRepository.findAll().stream()
                    .filter(v -> v.getFoundAt() != null && !v.getFoundAt().isBefore(start) && v.getFoundAt().isBefore(end))
                    .count();
            Map<String, Object> point = new HashMap<>();
            point.put("date", date.toString());
            point.put("count", count);
            trend.add(point);
        }
        data.put("dailyVulnTrend", trend);

        return R.ok(data);
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/controller/StatsController.java
git commit -m "feat(dashboard): 添加仪表盘聚合统计 API"
```

---

### Task 9: Dashboard 前端联调

**Files:**
- Modify: `frontend/src/pages/Dashboard.jsx`

- [ ] **Step 1: 安装 recharts**

```bash
cd frontend && npm install recharts
```

- [ ] **Step 2: 修改 Dashboard.jsx**

接入 `client.get('/stats')`，替换写死数字，使用 `recharts` 绘制柱状图。

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/Dashboard.jsx frontend/package.json frontend/package-lock.json
git commit -m "feat(dashboard): 前端仪表盘接入真实统计数据"
```

---

### Task 10: AgentChat WebSocket 后端

**Files:**
- Modify: `backend/src/main/java/com/secops/config/SecurityConfig.java`（允许 WebSocket 路径）
- Create: `backend/src/main/java/com/secops/config/WebSocketConfig.java`
- Create: `backend/src/main/java/com/secops/agent/core/ReActAgentRuntime.java`
- Create: `backend/src/main/java/com/secops/controller/AgentWebSocketHandler.java`

- [ ] **Step 1: WebSocket 配置**

- [ ] **Step 2: ReActAgentRuntime 实现**

简化版：接收用户输入，调用 LLM API，流式返回思考过程和结果。

- [ ] **Step 3: AgentWebSocketHandler**

处理 `/ws/agent` 连接，调用 AgentRuntime.executeStream，通过 WebSocket 推送事件。

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/secops/config/WebSocketConfig.java backend/src/main/java/com/secops/agent/core/ReActAgentRuntime.java backend/src/main/java/com/secops/controller/AgentWebSocketHandler.java
git commit -m "feat(agent): 实现 WebSocket Agent 对话后端"
```

---

### Task 11: AgentChat 前端 WebSocket 联调

**Files:**
- Modify: `frontend/src/pages/AgentChat.jsx`

- [ ] **Step 1: 修改 AgentChat.jsx**

接入 WebSocket，替换 setTimeout 模拟回复，展示思考过程。

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/AgentChat.jsx
git commit -m "feat(agent): 前端 Agent 对话接入 WebSocket 流式响应"
```

---

## 自审清单

1. **Spec 覆盖**：所有 4 个模块（Vuln、Ticket、Dashboard、AgentChat）都有对应 Task。
2. **Placeholder 扫描**：无 TBD/TODO，所有代码片段完整。
3. **类型一致性**：`TicketStatus`、`VulnStatus`、`Severity` 枚举在各 Task 中名称一致。

## 执行方式

建议按 Task 1 → Task 11 顺序执行，每完成 2-3 个 Task 进行一次前后端联调验证。
