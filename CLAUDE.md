# SecOps Agent 项目上下文

## 项目概述
SecOps Agent 是一个 **Agent 驱动的智能漏洞管理平台**。
不是单纯的扫描器，而是通过 AI Agent 自动分析扫描结果、去误报、生成修复建议、跟踪修复进度。

## 核心场景
1. 用户添加域名目标，通过 DNS TXT 验证所有权
2. 平台调度扫描引擎（Nuclei/Subfinder/Naabu）执行资产发现和漏洞检测
3. Agent 读取扫描原始输出，进行智能分析（ReAct 循环）
4. 生成结构化漏洞报告，自动创建修复工单
5. 开发者与 Agent 对话咨询漏洞详情和修复方案
6. 修复完成后自动重新扫描验证

## 技术栈
- **后端**：Java 17, Spring Boot 3.4, Spring Security, Spring Data JPA, PostgreSQL, Redis, Maven
- **前端**：React 19, Vite, Ant Design, Recharts, React Router
- **扫描引擎**：Nuclei, Subfinder, Naabu, Httpx（ProjectDiscovery 全家桶）
- **Agent**：ReAct 架构，LLM API（OpenAI / 本地模型）
- **部署**：Docker Compose

## 关键架构决策

### 扫描引擎集成
- 不重新发明扫描逻辑，通过 Java `ProcessBuilder` 调用开源工具
- 解析 JSON 标准输出，统一为 `ScanResult` 对象
- 扫描引擎以 Docker Service 形式独立部署，支持横向扩展

### Agent 运行时
- ReAct 循环：思考(Thought) → 行动(Action/ToolCall) → 观察(Observation)
- 流式输出：通过 WebSocket 向客户端实时推送思考过程
- 工具注册：扫描结果查询、漏洞知识库检索、网络搜索、代码解释器

### 安全合规
- 目标必须 DNS TXT 验证所有权
- 扫描频率限流，避免对目标造成压力
- 支持扫描窗口设置（避开业务高峰）

## 目录结构
```
secops-agent/
├── backend/
│   ├── scanner/engine/   扫描引擎接口与适配器
│   ├── agent/core/       Agent 运行时与上下文
│   ├── entity/           JPA 实体
│   ├── service/          业务服务
│   └── controller/       REST API + WebSocket
├── frontend/
│   ├── pages/            Dashboard, Targets, Scans, Vulns, Tickets, AgentChat
│   └── components/       公共组件与布局
├── scanner/              扫描引擎 Dockerfile
└── docker-compose.yml
```

## 数据库核心实体
- User / Team：用户与团队
- Target：扫描目标（域名、验证状态）
- ScanTask：扫描任务（类型、状态、进度、原始输出）
- Vulnerability：漏洞（名称、等级、描述、修复建议、关联工单）
- Ticket：修复工单（优先级、负责人、状态、验证结果）
- AgentSession：Agent 对话会话与记忆

## 开发规范
- 后端 REST API 统一返回 `R<T>` 包装
- 前端路由：/dashboard, /targets, /scans, /vulns, /tickets, /agent
- Agent 流式响应使用 WebSocket `/ws/agent`
- 扫描任务异步执行，状态通过 WebSocket 推送
