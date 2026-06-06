# 完整扫描系统设计方案

## 目标
将当前 demo 级别的扫描升级为可真正发现漏洞的生产级扫描系统。核心策略：**外部专业工具优先，容器化部署保障可用性**。

## 现状与差距

| 模块 | 现状 | 目标 |
|------|------|------|
| 引擎部署 | 依赖宿主机安装二进制，经常不可用 | Docker Compose 集成工具镜像，开箱即用 |
| 扫描深度 | 降级探测仅 6 个固定路径 | Nuclei 9000+ 模板、Subfinder 多源子域名发现 |
| 扫描类型 | 前端仅支持 FULL，后端流水线固定 | FULL/SUBDOMAIN/PORT/VULN 均可独立执行 |
| 实时进度 | 前端 3s 轮询 REST API | WebSocket 按阶段实时推送 |
| 结果处理 | 直接入库，无去重 | 按 (target, name, matched) 去重，关联历史 |
| Agent 联动 | 无 | 扫描完成自动触发 Agent 分析，生成修复建议并创建工单 |
| 调度控制 | 无 | 支持扫描速率、并发数限制 |

## 架构设计

### 1. 扫描引擎容器化

新增 `docker-compose.yml` 服务：

```yaml
services:
  nuclei:
    image: projectdiscovery/nuclei:latest
    volumes:
      - nuclei-templates:/root/nuclei-templates
    command: ["sh", "-c", "nuclei -ut && sleep infinity"]
    networks: [secops-net]

  subfinder:
    image: projectdiscovery/subfinder:latest
    networks: [secops-net]

  naabu:
    image: projectdiscovery/naabu:latest
    networks: [secops-net]

  httpx:
    image: projectdiscovery/httpx:latest
    networks: [secops-net]
```

后端适配器改为通过 `docker exec` 调用容器内命令，替代直接调用宿主机二进制。

### 2. 扫描类型拆分

`ScannerEngineService` 提供四个独立入口：

- `runFullScan(task)` — 完整流水线：子域名 → 端口 → 存活 → 漏洞
- `runSubdomainScan(task)` — 仅子域名发现
- `runPortScan(task)` — 仅端口扫描（对已知主机）
- `runVulnScan(task)` — 仅漏洞扫描（对已知 URL）

`ScanTaskService.createScanTask()` 根据 `scanType` 路由到对应方法。

### 3. WebSocket 实时推送

新增 STOMP/WebSocket 端点 `/ws/scans`，扫描每完成一个阶段推送事件：

```json
{
  "type": "SCAN_PROGRESS",
  "taskId": "SCAN-20260606-1234",
  "status": "RUNNING",
  "progress": 50,
  "stage": "PORT_SCAN",
  "message": "Naabu: 12 ports found"
}
```

前端 `ScanTasks.jsx` 订阅此频道，替代轮询。

### 4. 结果去重与关联

`VulnerabilityRepository` 新增查询：

```java
Optional<Vulnerability> findByTargetAndNameAndMatched(String target, String name, String matched);
```

扫描发现漏洞时，先按 `(target, name, matched)` 查询：
- 若已存在且状态为 OPEN → 更新 `foundAt` 和 `scanTask`，标记为"复现"
- 若已存在且状态为 FIXED → 更新为 REOPENED，触发告警
- 若不存在 → 新建记录

### 5. Agent 自动分析

扫描完成后，自动将 `rawOutput` 和发现的漏洞列表提交给 `AgentRuntime`：

- Agent 使用 ReAct 循环分析扫描结果
- 生成结构化修复建议（代码示例、配置修改）
- 对每个 CRITICAL/HIGH 漏洞自动创建 Ticket
- 分析过程通过 WebSocket 流式推送到 `/ws/agent`

### 6. 扫描限流

`ScannerEngineService` 增加并发控制：

- 使用 `Semaphore` 限制全局同时扫描数（默认 3）
- Nuclei 内部限速 `-rl 150`
- Naabu 内部限速 `-rate 1000`

## 数据流

```
用户发起扫描
  → ScanTaskController.create()
  → ScanTaskService.createScanTask()
  → ScannerEngineService.runXxxScan(task)
    → 阶段1: Subfinder / DNS 降级
      → WebSocket 推送 PROGRESS
    → 阶段2: Naabu / Socket 降级
      → WebSocket 推送 PROGRESS
    → 阶段3: Httpx / HTTP 降级
      → WebSocket 推送 PROGRESS
    → 阶段4: Nuclei / HTTP 降级
      → WebSocket 推送 PROGRESS
    → 漏洞去重入库
    → WebSocket 推送 COMPLETED
    → 触发 Agent 分析
      → Agent 生成修复建议
      → 自动创建 Ticket
```

## 错误处理

- 任一阶段引擎不可用 → 自动降级到 Java 原生探测，不打断流水线
- 阶段超时（默认 300s）→ 记录该阶段失败，继续下一阶段
- 整体异常 → 任务标记 FAILED，WebSocket 推送错误信息

## 测试策略

- 集成测试：Mock `ProcessBuilder` / `docker exec` 返回预设 JSONL，验证解析和入库
- WebSocket 测试：使用 Spring `TestChannelInterceptor` 验证消息推送
- 去重测试：重复扫描同一目标，验证漏洞不会重复创建

## 前端变更

- `ScanTasks.jsx`：扫描类型选择器增加 SUBDOMAIN / PORT / VULN 选项；接入 WebSocket 实时更新进度
- `Vulnerabilities.jsx`：增加"复现次数"、"首次发现时间"、"关联扫描任务"列

## 文件变更清单

后端：
- `docker-compose.yml` — 新增扫描引擎服务
- `scanner/engine/SubfinderScanner.java` — 改为 docker exec 调用
- `scanner/engine/NaabuScanner.java` — 改为 docker exec 调用
- `scanner/engine/HttpxScanner.java` — 改为 docker exec 调用
- `scanner/engine/NucleiScanner.java` — 改为 docker exec 调用，支持模板过滤
- `service/ScannerEngineService.java` — 拆分扫描类型、WebSocket 推送、去重逻辑、并发控制
- `service/ScanTaskService.java` — 根据类型路由
- `controller/ScanTaskController.java` — 可能无需变更
- `repository/VulnerabilityRepository.java` — 新增去重查询方法
- `config/WebSocketConfig.java` — 新增 STOMP 配置和扫描进度 topic
- 新增 `controller/ScanProgressWebSocketController.java`

前端：
- `pages/ScanTasks.jsx` — WebSocket 接入、扫描类型选项
- `pages/Vulnerabilities.jsx` — 显示复现信息
