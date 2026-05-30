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

## 开发工作流（Superpowers Skills）

本项目全流程使用 Superpowers skills 套件，所有开发任务必须遵循以下工作流：

| 场景 | Skill |
|------|-------|
| 任何创造性工作（新功能、新组件、修改行为） | `/superpowers:brainstorming` |
| 多步骤实现任务（有规格或需求文档） | `/superpowers:writing-plans` |
| 执行已有实现计划 | `/superpowers:executing-plans` |
| 功能开发或 Bug 修复 | `/superpowers:test-driven-development` |
| 遇到 Bug、测试失败、异常行为 | `/superpowers:systematic-debugging` |
| 即将声称工作完成/通过 | `/superpowers:verification-before-completion` |
| 完成实现，需要集成 | `/superpowers:finishing-a-development-branch` |
| 完成任务后请求审查 | `/superpowers:requesting-code-review` |
| 收到代码审查反馈 | `/superpowers:receiving-code-review` |
| 2+ 个独立任务可并行 | `/superpowers:dispatching-parallel-agents` |

**前端专项 Skill**：
| 场景 | Skill |
|------|-------|
| 前端 UI 开发（组件、页面、交互、样式） | `/agent-skills:frontend-ui-engineering` |
| 使用浏览器 DevTools 测试和调试 | `/agent-skills:browser-testing-with-devtools` |
| API 与接口设计 | `/agent-skills:api-and-interface-design` |

**后端/通用 Skill**：
| 场景 | Skill |
|------|-------|
| 代码简化与重构 | `/agent-skills:code-simplification` |
| 性能优化 | `/agent-skills:performance-optimization` |
| 安全审查与加固 | `/agent-skills:security-and-hardening` |
| 测试驱动开发（TDD） | `/agent-skills:test-driven-development` |
| 调试与错误恢复 | `/agent-skills:debugging-and-error-recovery` |
| 代码审查与质量 | `/agent-skills:code-review-and-quality` |

关键规则：
- **编码前必须先 brainstorming**：任何创造性工作（新功能、新组件、修改行为）之前必须调用 `/superpowers:brainstorming`
- **TDD 优先**：先写测试，再写实现（调用 `/superpowers:test-driven-development`）
- **证据优先于断言**：声称完成前必须有验证命令的输出作为证据（调用 `/superpowers:verification-before-completion`）
- **收到审查反馈时保持严谨**：不盲目同意，技术上验证每条反馈（调用 `/superpowers:receiving-code-review`）
- **前端开发优先使用专用 skill**：涉及 UI/UX、组件开发、页面布局时，优先调用 `/agent-skills:frontend-ui-engineering`

## 提交规范

遵循 Conventional Commits：

```
<类型>(<范围>): <描述>
```

**类型**：`feat` / `fix` / `hotfix` / `perf` / `build` / `ci` / `chore` / `docs` / `refactor` / `revert` / `style` / `test`

**范围（括号内容）**：使用英文模块名，如 `feat(target): ...`、`fix(scanner): ...`、`docs(api): ...`

**描述**：至少 5 个字符，使用中文

**分支名规范**：`main` | `dev` | `feature/xxx` | `fix/xxx` | `refactor/xxx`

**开发分支**：所有开发工作建议在独立分支进行，通过 PR 合并到 `main`。

注意：commit message 的描述部分**必须使用中文**。

## 项目特定规范

- **技术栈**：Java 17, Spring Boot 3.4, React 19, Vite, Ant Design, Maven
- **运行**：
  - 后端：`cd backend && mvn spring-boot:run`
  - 前端：`cd frontend && npm run dev`
  - 基础设施：`docker compose up postgres redis`
- **注释语言**：代码注释、文档字符串使用中文
- **Commit 语言**：Commit message 描述部分使用中文（类型/范围仍遵循 Conventional Commits 英文规范）
- **`.claude/` 目录**：Claude Code 本地配置目录，**必须**加入 `.gitignore`，禁止提交到仓库

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

## 开发自测流程（改完必须自测）

**核心原则**：任何代码修改完成后，必须在提交前自行验证，不能依赖用户当测试员。

### 1. 后端单元测试验证

```bash
cd backend
mvn test
```

验证 checklist：
- [ ] 所有新增单元测试通过
- [ ] 旧测试未出现回归失败

### 2. 后端启动验证

```bash
cd backend
mvn spring-boot:run
```

验证 checklist：
- [ ] 应用正常启动，无启动期异常
- [ ] PostgreSQL 连接成功
- [ ] Redis 连接成功（如使用）
- [ ] `/actuator/health` 返回 UP

### 3. 前端启动验证

```bash
cd frontend
npm run dev
```

验证 checklist：
- [ ] Vite 开发服务器正常启动
- [ ] 页面无白屏/崩溃
- [ ] 控制台无未处理异常
- [ ] 路由切换正常

### 4. 前后端联调验证

```bash
# 终端 1：启动后端
cd backend && mvn spring-boot:run

# 终端 2：启动前端
cd frontend && npm run dev
```

验证 checklist：
- [ ] 前端能正确调用后端 API（无 CORS 错误）
- [ ] 数据加载/提交正常
- [ ] 页面状态与后端数据一致

### 5. Docker Compose 全栈验证

```bash
docker compose up --build
```

验证 checklist：
- [ ] PostgreSQL 容器正常启动
- [ ] Redis 容器正常启动
- [ ] 后端容器正常启动并能连上数据库
- [ ] 前端容器正常构建并可通过 Nginx 访问

### 6. 常见问题自诊

| 现象 | 排查方向 |
|------|----------|
| 后端启动失败 | 检查 PostgreSQL/Redis 是否已启动；检查 `application.yml` 端口/密码配置 |
| Maven 编译失败 | 检查 Java 版本（需 17+）；检查 `pom.xml` 依赖冲突 |
| 前端白屏 | 检查 Vite 控制台报错；检查 `main.jsx` / `App.jsx` 语法错误 |
| API 404 | 检查后端 Controller 路径；检查前端请求 URL 和端口 |
| CORS 错误 | 检查 `SecurityConfig.corsConfigurationSource()` 配置；确认前端地址在允许列表 |
| 数据库连接失败 | 检查 `spring.datasource.url`；确认 PostgreSQL 容器端口映射 |
