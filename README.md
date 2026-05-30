# SecOps Agent

Agent 驱动的智能漏洞扫描与管理平台。

## 核心特性

- **Agent 智能分析**：不是简单的扫描器输出，AI Agent 自动解读结果、去误报、生成修复建议
- **全流程闭环**：资产发现 → 漏洞扫描 → Agent 分析 → 工单跟踪 → 修复验证
- **开源引擎集成**：基于 Nuclei、Subfinder、Naabu、OWASP ZAP 等成熟工具链
- **合规扫描**：目标所有权验证（DNS TXT），仅扫描授权资产

## 技术栈

| 层级 | 技术 |
|------|------|
| 前端 | React 19 + Vite + Ant Design + Recharts |
| 后端 | Spring Boot 3.x + JPA + WebSocket + Redis |
| 数据库 | PostgreSQL + Redis（任务队列） |
| 扫描引擎 | Nuclei + Subfinder + Naabu + Httpx（Docker） |
| Agent | ReAct 循环 + LLM API |
| 部署 | Docker Compose |

## 快速启动

```bash
# 1. 启动基础设施
docker-compose up -d postgres redis

# 2. 启动后端
cd backend
./mvnw spring-boot:run

# 3. 启动前端
cd frontend
npm install
npm run dev
```

前端: http://localhost:5173  
后端: http://localhost:8080

## 扫描流水线

```
Target (example.com)
    ↓
subfinder → 子域名列表
    ↓
naabu → 存活端口
    ↓
httpx → 存活 Web 服务 + 技术栈指纹
    ↓
nuclei → 漏洞扫描（JSON 输出）
    ↓
Agent 分析 → 去误报 + 风险评级 + 修复建议
    ↓
漏洞入库 → 工单创建 → 修复跟踪
```

## 项目结构

```
secops-agent/
├── backend/          Spring Boot 后端
│   ├── scanner/      扫描引擎适配器
│   ├── agent/        Agent 运行时（ReAct）
│   └── ...
├── frontend/         React 前端
│   ├── pages/        页面（仪表盘、目标、扫描、漏洞、工单、Agent）
│   └── ...
├── scanner/          扫描引擎 Docker 镜像
├── docker-compose.yml
└── README.md
```

## 法律声明

本平台仅用于扫描**用户拥有或明确授权**的资产。内置 DNS TXT 验证机制确保目标所有权。
