# 前后端接口联调完善设计文档

## 背景

项目中 Targets 和 ScanTasks 已完成前后端联调，但 Dashboard、Vulnerabilities、Tickets、AgentChat 四个模块仍为硬编码或占位状态。本次设计覆盖这四个模块的完整联调。

## 实施顺序（方案 A：数据流依赖顺序）

1. Vulnerability → 2. Ticket → 3. Dashboard → 4. AgentChat

---

## 1. 漏洞管理 (Vulnerability)

### 后端

- 新建 `VulnerabilityController`
  - `GET /api/vulns` — 分页列表，支持按 `severity`、`status`、`scanTaskId` 筛选
  - `GET /api/vulns/{id}` — 详情
  - `PATCH /api/vulns/{id}/status` — 更新状态（OPEN → FIXED / FALSE_POSITIVE）
- 扩展 `VulnerabilityRepository`：增加 `findByScanTaskId`、`findByStatus`、`findBySeverity` 方法

### 前端

- `Vulnerabilities.jsx` 接入 `client.get('/vulns')`
- 表格保留现有字段，增加**详情**弹窗（展示 description、matched、scanner）
- **创建工单**按钮：调用 `POST /api/tickets`（关联当前漏洞 ID）

---

## 2. 修复工单 (Ticket)

### 后端

- 新建 `Ticket` 实体：
  ```java
  @Entity @Table(name = "tickets")
  class Ticket {
    Long id;
    String title;
    Severity priority;
    TicketStatus status;   // OPEN / IN_PROGRESS / CLOSED
    String assignee;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    @OneToOne Vulnerability vulnerability;
  }
  ```
- 新建 `TicketStatus` 枚举
- 新建 `TicketRepository`、`TicketService`、`TicketController`
  - `GET /api/tickets` — 列表
  - `POST /api/tickets` — 创建（接受 `vulnerabilityId`，自动复制 title/priority）
  - `PATCH /api/tickets/{id}` — 更新状态/负责人
  - `DELETE /api/tickets/{id}` — 删除

### 前端

- `Tickets.jsx` 接入 `client.get('/tickets')`
- 操作列：**处理**（状态流转）、**关闭**

---

## 3. 仪表盘 (Dashboard)

### 后端

- 新建 `StatsController`，`GET /api/stats`
  - 目标总数 `targetCount`
  - 扫描任务总数 `scanTaskCount`
  - 漏洞总数 `vulnCount`
  - 工单总数 `ticketCount`
  - 各等级漏洞分布 `severityCounts`
  - 最近 5 条漏洞 `recentVulns`
  - 近 7 天漏洞发现量 `dailyVulnTrend`

### 前端

- 替换写死数字为真实 API 数据
- 使用 `recharts` 绘制风险趋势图（近 7 天漏洞发现量柱状图）
- 最新漏洞列表接入真实数据

---

## 4. Agent 对话 (AgentChat)

### 后端

- 实现 `AgentRuntime` 接口的具体类（如 `ReActAgentRuntime`）
- 新建 WebSocket Handler `/ws/agent`
  - 接收用户消息
  - 调用 AgentRuntime.executeStream 进行 ReAct 循环
  - 流式推送事件：`think`、`action`、`observe`、`complete`
- LLM 调用使用配置中的 OpenAI API（`agent.llm` 配置已存在）

### 前端

- WebSocket 连接 `/ws/agent`
- 实时展示思考过程（灰色小字）和最终回复
- 保留现有 UI 样式

---

## 数据流

```
ScanTask 执行 → Nuclei 发现漏洞 → vulnerabilityRepository.save()
                                      ↓
                         Vulnerability 页面展示 ← 用户查看
                                      ↓
                         用户点击"创建工单" → POST /api/tickets
                                      ↓
                         Ticket 页面展示工单 ← 用户处理/关闭
                                      ↓
                         Dashboard 聚合统计 ← 实时刷新
```

## 依赖

- 后端：Spring Data JPA、Spring WebSocket、Recharts（前端）
- 前端已有 `axios` client、Ant Design 组件库
