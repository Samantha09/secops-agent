# 扫描引擎层改造（第一批）设计方案

## 目标
将当前依赖宿主机二进制的扫描引擎升级为容器化部署，支持扫描类型拆分、WebSocket 实时进度推送和并发限流。

## 范围
本批次包含 4 个改进点：
1. 扫描引擎容器化
2. 扫描类型拆分
3. WebSocket 实时推送扫描进度
4. 扫描限流

## 现状与差距

| 模块 | 现状 | 目标 |
|------|------|------|
| 引擎部署 | 依赖宿主机安装二进制，经常不可用 | Docker Compose 集成工具镜像，开箱即用 |
| 扫描类型 | 前端仅支持 FULL，后端流水线固定 | FULL/SUBDOMAIN/PORT/VULN 均可独立执行 |
| 实时进度 | 前端 3s 轮询 REST API | WebSocket 按阶段实时推送 |
| 调度控制 | 无 | 支持扫描速率、并发数限制 |

---

## 1. 扫描引擎容器化

### 1.1 Docker Compose 服务定义

新增 4 个扫描引擎服务到 `docker-compose.yml`：

```yaml
services:
  nuclei:
    image: projectdiscovery/nuclei:latest
    container_name: secops-nuclei
    volumes:
      - nuclei-templates:/root/nuclei-templates
    command: ["sh", "-c", "nuclei -ut && sleep infinity"]
    networks: [secops-net]

  subfinder:
    image: projectdiscovery/subfinder:latest
    container_name: secops-subfinder
    networks: [secops-net]

  naabu:
    image: projectdiscovery/naabu:latest
    container_name: secops-naabu
    networks: [secops-net]

  httpx:
    image: projectdiscovery/httpx:latest
    container_name: secops-httpx
    networks: [secops-net]
```

并更新 `backend` 服务：
- 挂载 `/var/run/docker.sock:/var/run/docker.sock`
- 新增环境变量 `DOCKER_HOST=unix:///var/run/docker.sock`

### 1.2 后端适配器改造

四个扫描适配器统一改为通过 `docker exec` 调用容器内命令：

```java
ProcessBuilder pb = new ProcessBuilder(
    "docker", "exec", "secops-subfinder",
    "subfinder", "-d", target, "-all"
);
```

`isAvailable()` 方法改为检查容器是否运行：
```java
ProcessBuilder pb = new ProcessBuilder("docker", "exec", containerName, binaryName, "-version");
```

各适配器映射关系：

| 适配器 | 容器名 | 容器内命令 |
|--------|--------|-----------|
| SubfinderScanner | secops-subfinder | subfinder |
| NaabuScanner | secops-naabu | naabu |
| HttpxScanner | secops-httpx | httpx |
| NucleiScanner | secops-nuclei | nuclei |

### 1.3 降级策略保留

任一容器不可用时，自动降级到 Java 原生探测，不打断流水线。降级逻辑保持现有实现不变。

---

## 2. 扫描类型拆分

### 2.1 扫描类型枚举

`ScanType.java` 已存在：
```java
public enum ScanType {
    FULL, SUBDOMAIN, PORT, VULN
}
```

### 2.2 ScannerEngineService 独立入口

```java
@Async
public void runFullScan(ScanTask task) { ... }

@Async
public void runSubdomainScan(ScanTask task) { ... }

@Async
public void runPortScan(ScanTask task) { ... }

@Async
public void runVulnScan(ScanTask task) { ... }
```

各类型执行逻辑：

| 类型 | 执行阶段 |
|------|----------|
| FULL | Subfinder → Naabu → Httpx → Nuclei |
| SUBDOMAIN | Subfinder 仅 |
| PORT | Naabu 仅（对目标直接扫描） |
| VULN | Nuclei 仅（对目标直接扫描） |

### 2.3 ScanTaskService 路由

```java
public ScanTask createScanTask(Long targetId, ScanType scanType) {
    // ... 创建任务 ...
    switch (scanType) {
        case FULL -> scannerEngineService.runFullScan(task);
        case SUBDOMAIN -> scannerEngineService.runSubdomainScan(task);
        case PORT -> scannerEngineService.runPortScan(task);
        case VULN -> scannerEngineService.runVulnScan(task);
    }
    return task;
}
```

### 2.4 前端扫描类型选择器

`ScanTasks.jsx` 创建任务弹窗增加类型选择：FULL（完整扫描）、SUBDOMAIN（子域名发现）、PORT（端口扫描）、VULN（漏洞扫描）。

---

## 3. WebSocket 实时推送扫描进度

### 3.1 复用现有 WebSocket 基础设施

项目已有 `/ws/agent` 纯 WebSocket 端点。新增扫描进度消息类型，复用同一 WebSocket 连接，避免引入 STOMP 增加复杂度。

### 3.2 扫描进度消息格式

```json
{
  "type": "SCAN_PROGRESS",
  "taskId": "SCAN-20260609-1234",
  "status": "RUNNING",
  "progress": 50,
  "stage": "PORT_SCAN",
  "message": "Naabu: 12 ports found"
}
```

阶段定义：
- `SUBDOMAIN_SCAN` — 子域名发现
- `PORT_SCAN` — 端口扫描
- `HTTP_PROBE` — 存活探测
- `VULN_SCAN` — 漏洞扫描
- `COMPLETED` — 全部完成
- `FAILED` — 执行失败

### 3.3 ScannerEngineService 推送逻辑

每完成一个阶段，调用 WebSocket 推送方法：

```java
private void pushProgress(ScanTask task, String stage, String message, int progress) {
    // 通过 WebSocket 发送 SCAN_PROGRESS 消息
}
```

### 3.4 前端接入

`ScanTasks.jsx`：
- 建立 WebSocket 连接（复用现有连接逻辑或新建连接）
- 监听 `SCAN_PROGRESS` 消息类型
- 根据 `taskId` 更新对应任务的进度条和状态文本
- 移除现有的 3s 轮询逻辑，或保留轮询作为降级

---

## 4. 扫描限流

### 4.1 全局并发控制

`ScannerEngineService` 增加 `Semaphore`：

```java
private final Semaphore scanSemaphore = new Semaphore(3);
```

每个扫描方法开始时 `acquire()`，结束时 `release()`：

```java
@Async
public void runFullScan(ScanTask task) {
    if (!scanSemaphore.tryAcquire()) {
        task.setStatus(ScanStatus.QUEUED);
        scanTaskRepository.save(task);
        scanSemaphore.acquire(); // 阻塞等待
    }
    try {
        // ... 扫描逻辑 ...
    } finally {
        scanSemaphore.release();
    }
}
```

### 4.2 引擎内部限速

| 引擎 | 限速参数 |
|------|----------|
| Nuclei | `-rl 150`（每秒 150 请求） |
| Naabu | `-rate 1000`（每秒 1000 包） |

在 `NucleiScanner` 和 `NaabuScanner` 的 `scan()` 方法中追加对应参数。

---

## 数据流

```
用户发起扫描
  → ScanTaskController.create()
  → ScanTaskService.createScanTask()
    → 根据 scanType 路由到 ScannerEngineService.runXxxScan(task)
      → scanSemaphore.acquire() 获取许可
      → 阶段1: Subfinder / DNS 降级
        → WebSocket 推送 PROGRESS (progress=30)
      → 阶段2: Naabu / Socket 降级
        → WebSocket 推送 PROGRESS (progress=50)
      → 阶段3: Httpx / HTTP 降级
        → WebSocket 推送 PROGRESS (progress=70)
      → 阶段4: Nuclei / HTTP 降级
        → WebSocket 推送 PROGRESS (progress=90)
      → WebSocket 推送 COMPLETED (progress=100)
      → scanSemaphore.release()
```

---

## 错误处理

- **容器不可用** → 自动降级到 Java 原生探测，不打断流水线
- **阶段超时**（默认 300s）→ 记录该阶段失败，继续下一阶段
- **整体异常** → 任务标记 FAILED，WebSocket 推送错误信息，释放 Semaphore
- **限流队列** → 任务状态先设为 QUEUED，获取许可后开始执行

---

## 测试策略

- **集成测试**：Mock `docker exec` 返回预设 JSONL，验证解析和状态流转
- **WebSocket 测试**：验证 `SCAN_PROGRESS` 消息格式和推送时机
- **限流测试**：并发发起多个扫描任务，验证 Semaphore 阻塞行为
- **容器可用性测试**：验证 `isAvailable()` 在容器运行/停止时的正确表现

---

## 文件变更清单

后端：
- `docker-compose.yml` — 新增扫描引擎服务，backend 挂载 docker.sock
- `scanner/engine/SubfinderScanner.java` — 改为 docker exec 调用
- `scanner/engine/NaabuScanner.java` — 改为 docker exec 调用，追加 -rate 1000
- `scanner/engine/HttpxScanner.java` — 改为 docker exec 调用
- `scanner/engine/NucleiScanner.java` — 改为 docker exec 调用，追加 -rl 150，支持模板过滤
- `service/ScannerEngineService.java` — 拆分扫描类型、WebSocket 推送、Semaphore 限流
- `service/ScanTaskService.java` — 根据类型路由
- `config/WebSocketConfig.java` — 如需要扩展配置

前端：
- `pages/ScanTasks.jsx` — WebSocket 接入扫描进度、扫描类型选项

---

## 与第二批的关系

第一批完成后，扫描引擎层具备：容器化部署、多类型扫描、实时进度、并发控制。

第二批在此基础上增加：
- **结果去重**：`VulnerabilityRepository.findByTargetAndNameAndMatched()`，入库前查重
- **Agent 自动分析**：扫描完成后自动触发 `AgentRuntime`，生成修复建议并创建 Ticket
