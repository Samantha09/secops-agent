# SecOps Agent MVP 设计文档

## 1. 项目背景与目标

SecOps Agent 是一个 **Agent 驱动的智能漏洞管理平台**。MVP 目标是在最短时间内验证核心假设：从添加扫描目标、执行真实漏洞扫描、Agent 智能分析到工单跟踪的全流程是否跑得通。

## 2. MVP 范围

| 模块 | MVP 包含 | MVP 不包含（后续迭代） |
|------|---------|---------------------|
| 认证 | JWT 注册/登录/密码加密 | 邮箱验证、密码找回、Token 刷新 |
| 目标管理 | 添加域名、DNS TXT 验证 | 批量导入、IP/CIDR 目标、自动轮询验证 |
| 扫描调度 | 单目标全量扫描、真实引擎调用 | 批量扫描、定时扫描、扫描窗口、频率限流 |
| 扫描引擎 | Nuclei/Subfinder/Naabu/Httpx 适配器 | 自定义模板、分布式扫描引擎 |
| 漏洞管理 | 列表、详情、标记误报 | 漏洞合并、CVSS 评分、漏洞知识库 |
| 工单系统 | 从漏洞创建、状态跟踪 | 指派通知、SLA、修复验证重扫 |
| Agent | WebSocket 流式对话、Mock ReAct | 真实 LLM 调用、多工具 Agent、代码解释器 |
| 前端 | 6 个功能页面对接真实 API | 数据可视化图表、移动端适配 |

## 3. 架构概览

```
┌──────────────┐     ┌──────────────┐     ┌─────────────────────┐
│   React 19   │────▶│  Spring Boot │────▶│   PostgreSQL +      │
│   (Vite)     │◀────│   REST API   │◀────│   Redis             │
└──────┬───────┘     └──────┬───────┘     └─────────────────────┘
       │ WebSocket           │
       ▼                     ▼
┌──────────────┐     ┌─────────────────────┐
│ Agent Chat   │     │  Scanner Engines    │
│ (Mock ReAct) │     │  (ProcessBuilder)   │
└──────────────┘     └─────────────────────┘
```

## 4. 数据库设计

### 4.1 实体关系

```
Team 1───* User
Team 1───* Target
Target 1───* ScanTask
ScanTask 1───* Vulnerability
Vulnerability 1───* Ticket
User 1───* AgentSession
AgentSession 1───* AgentMessage
```

### 4.2 实体定义

```java
@Entity
public class Team {
    @Id @GeneratedValue private Long id;
    private String name;
}

@Entity
public class User {
    @Id @GeneratedValue private Long id;
    private String username;
    private String password; // BCrypt
    private String email;
    private String role; // ADMIN / USER
    @ManyToOne private Team team;
}

@Entity
public class Target {
    @Id @GeneratedValue private Long id;
    private String domain;
    private String verificationToken;
    @Enumerated(EnumType.STRING) private VerificationStatus status;
    private LocalDateTime verifiedAt;
    private LocalDateTime createdAt;
    @ManyToOne private Team team;
}

@Entity
public class ScanTask {
    @Id @GeneratedValue private Long id;
    private String taskId;
    @ManyToOne private Target target;
    @Enumerated(EnumType.STRING) private ScanStatus status;
    private int progress;
    private String scanType;
    @Column(length = 50000) private String rawOutput;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String errorMessage;
}

@Entity
public class Vulnerability {
    @Id @GeneratedValue private Long id;
    private String name;
    @Enumerated(EnumType.STRING) private Severity severity;
    private String description;
    private String matched;
    private String target;
    private String scanner;
    private LocalDateTime foundAt;
    @Enumerated(EnumType.STRING) private VulnStatus status;
    @ManyToOne private ScanTask scanTask;
}

@Entity
public class Ticket {
    @Id @GeneratedValue private Long id;
    private String ticketId;
    private String title;
    private String description;
    @Enumerated(EnumType.STRING) private Priority priority;
    @Enumerated(EnumType.STRING) private TicketStatus status;
    private String assignee;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    @ManyToOne private Vulnerability vulnerability;
}

@Entity
public class AgentSession {
    @Id @GeneratedValue private Long id;
    private String sessionId;
    private String userId;
    private LocalDateTime createdAt;
    @OneToMany(cascade = CascadeType.ALL) private List<AgentMessage> messages;
}

@Entity
public class AgentMessage {
    @Id @GeneratedValue private Long id;
    private String role;
    private String content;
    private LocalDateTime createdAt;
}
```

## 5. 切片详细设计

### 切片 1：用户注册/登录

**目标**：实现完整的 JWT 认证系统。

**API**：
- `POST /api/auth/register` — 注册并返回 JWT
- `POST /api/auth/login` — 登录并返回 JWT
- `GET /api/auth/me` — 当前用户信息

**前端**：新增 `/login`、`/register` 路由，未登录重定向，Axios 自动携带 Token。

**边界**：跳过邮箱验证、密码找回、Token 刷新；注册时自动创建默认 Team。

### 切片 2：目标管理 + DNS TXT 验证

**目标**：用户可以添加域名目标，通过 DNS TXT 验证所有权。

**API**：
- `POST /api/targets` — 添加目标，生成 `verificationToken`
- `GET /api/targets` — 列表（分页）
- `DELETE /api/targets/{id}` — 删除目标
- `POST /api/targets/{id}/verify` — 执行 `dig TXT <domain>` 验证

**DNS 验证逻辑**：
1. 后端生成 UUID 作为 `verificationToken`
2. 用户将 `_secops.<domain> TXT "secops-verify=<token>"` 添加到 DNS
3. 用户点击"验证"，后端调用 `dig +short TXT <domain>` 查询
4. 匹配到包含 `secops-verify=<token>` 的记录则标记为 VERIFIED

**前端**：Targets 页对接真实 API，添加目标弹窗展示需添加的 DNS 记录，验证状态列展示 PENDING/VERIFIED。

### 切片 3：扫描任务 + 真实引擎调用 + 结果入库

**目标**：从已验证目标发起扫描，真实调用开源工具，解析结果入库。

**扫描流水线**（串行执行）：
```
Target → subfinder → naabu → httpx → nuclei → 解析 JSON → Vulnerability 入库
```

**扫描引擎适配器**：
实现 `ScannerEngine` 接口的四个适配器，统一通过 `ProcessBuilder` 调用命令行工具：
- `SubfinderScanner` — 子域名发现
- `NaabuScanner` — 端口扫描
- `HttpxScanner` — 存活探测 + 技术栈指纹
- `NucleiScanner` — 漏洞扫描（`-json` 输出）

**调度**：Spring `@Async` 线程池异步执行，每阶段更新 `ScanTask.progress`。

**API**：
- `POST /api/scans` — 创建扫描任务
- `GET /api/scans` — 任务列表
- `GET /api/scans/{id}` — 任务详情 + 原始输出
- `GET /api/scans/{id}/vulns` — 该任务发现的漏洞

**前端**：ScanTasks 页对接真实 API，发起扫描弹窗选择目标+类型，进度条轮询更新。

### 切片 4：漏洞管理 + 修复工单

**目标**：展示扫描发现的漏洞，支持创建修复工单。

**API**：
- `GET /api/vulns` — 漏洞列表（支持 severity/status/target 过滤）
- `GET /api/vulns/{id}` — 漏洞详情（含原始输出片段）
- `POST /api/vulns/{id}/false-positive` — 标记误报
- `POST /api/tickets` — 从漏洞创建工单
- `GET /api/tickets` — 工单列表
- `PUT /api/tickets/{id}` — 更新状态/负责人

**前端**：Vulnerabilities 页对接真实 API，增加筛选和详情弹窗；Tickets 页对接真实 API，状态可编辑。

**边界**：工单不实现通知机制；修复后自动重扫标记为后续迭代。

### 切片 5：Agent Chat + WebSocket 流式输出（Mock）

**目标**：实现 WebSocket 流式对话体验，模拟 ReAct 循环。

**WebSocket 端点**：`/ws/agent/{sessionId}`

**消息协议**：
```json
{ "type": "CHAT",    "content": "用户输入" }
{ "type": "THOUGHT", "content": "思考过程..." }
{ "type": "ACTION",  "tool": "query_vulns", "params": "{}" }
{ "type": "OBSERVE", "content": "工具返回结果..." }
{ "type": "COMPLETE","content": "最终回复" }
```

**Agent 运行时**：实现 `AgentRuntime` 接口，但内部不走真实 LLM。按固定时序推送 `onThink` → `onAction` → `onObserve` → `onComplete`，最终回复基于查询参数和真实漏洞数据动态拼接模板。

**工具注册**（MVP 只实现 2 个）：
- `query_vulns` — 查询漏洞库
- `query_scan_results` — 查询扫描结果

**API**：
- `POST /api/agent/sessions` — 创建会话
- `GET /api/agent/sessions` — 会话列表
- WebSocket `/ws/agent/{sessionId}` — 流式对话

**前端**：AgentChat 页对接 WebSocket，消息类型区分样式（thought 灰色斜体、action 蓝色卡片、observe 绿色卡片），新增会话列表侧边栏。

## 6. API 汇总

| 端点 | 方法 | 描述 |
|------|------|------|
| `/api/auth/register` | POST | 用户注册 |
| `/api/auth/login` | POST | 用户登录 |
| `/api/auth/me` | GET | 当前用户 |
| `/api/targets` | GET/POST | 目标列表/创建 |
| `/api/targets/{id}` | DELETE | 删除目标 |
| `/api/targets/{id}/verify` | POST | DNS TXT 验证 |
| `/api/scans` | GET/POST | 扫描任务列表/创建 |
| `/api/scans/{id}` | GET | 扫描任务详情 |
| `/api/scans/{id}/vulns` | GET | 任务漏洞列表 |
| `/api/vulns` | GET | 漏洞列表 |
| `/api/vulns/{id}` | GET | 漏洞详情 |
| `/api/vulns/{id}/false-positive` | POST | 标记误报 |
| `/api/tickets` | GET/POST | 工单列表/创建 |
| `/api/tickets/{id}` | PUT | 更新工单 |
| `/api/agent/sessions` | GET/POST | Agent 会话列表/创建 |
| `/ws/agent/{sessionId}` | WS | Agent 流式对话 |

## 7. 前端页面与路由

| 路由 | 页面 | 功能 |
|------|------|------|
| `/login` | 登录页 | JWT 登录 |
| `/register` | 注册页 | JWT 注册 |
| `/dashboard` | Dashboard | 统计卡片（真实数据） |
| `/targets` | Targets | 目标管理 + DNS 验证 |
| `/scans` | ScanTasks | 扫描任务 + 发起扫描 |
| `/vulns` | Vulnerabilities | 漏洞列表 + 筛选 + 创建工单 |
| `/tickets` | Tickets | 工单列表 + 状态更新 |
| `/agent` | AgentChat | Agent 对话 + WebSocket |

## 8. 技术决策

1. **认证**：Spring Security + JWT，密码 BCrypt 加密。MVP 不实现 Refresh Token。
2. **DNS 验证**：使用 Java `ProcessBuilder` 调用系统 `dig` 命令。若环境无 `dig`，降级为 Java DNS 解析。
3. **扫描调度**：Spring `@Async` + 线程池。MVP 不引入消息队列。
4. **引擎适配器**：每个工具一个适配器类，统一实现 `ScannerEngine` 接口。通过 `application.yml` 配置工具路径。
5. **Agent**：MVP 不调用真实 LLM，通过模板 + 真实数据拼接回复，但完整保留 ReAct 流式协议，方便后续接入真实 LLM。
6. **前端状态管理**：MVP 使用 React `useState` + `useEffect`，不引入 Redux/Zustand。

## 9. 后续迭代方向

1. 接入真实 LLM API（OpenAI/Claude/本地模型）
2. 扫描引擎 Docker 化，支持横向扩展
3. 消息队列（Redis Stream/RabbitMQ）替代 `@Async`
4. 漏洞知识库 + 自动去误报
5. 修复后自动重扫验证
6. 通知系统（邮件/飞书/钉钉）
7. Dashboard 数据可视化图表（Recharts）
8. 批量扫描、定时扫描、扫描窗口
