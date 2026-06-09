# 扫描引擎层改造（第一批）实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将扫描引擎容器化部署，支持 FULL/SUBDOMAIN/PORT/VULN 四种扫描类型独立执行，通过 WebSocket 实时推送进度，并限制全局并发数为 3。

**Architecture:** 使用 Docker Compose 运行 ProjectDiscovery 工具容器，backend 通过 `docker exec` 调用；各扫描适配器将目标列表写入共享 volume `/workspace`，扫描容器从同一 volume 读取；新增 `/ws/scans` WebSocket 端点广播进度；`ScannerEngineService` 用 `Semaphore(3)` 控制并发。

**Tech Stack:** Java 17, Spring Boot 3.4, Spring WebSocket, Docker Compose, React 19, Ant Design

---

## 文件结构映射

| 文件 | 操作 | 职责 |
|------|------|------|
| `docker-compose.yml` | 修改 | 新增 nuclei/subfinder/naabu/httpx 服务 + 共享 volume + backend 挂载 docker.sock |
| `backend/Dockerfile` | 修改 | 安装 `docker-cli`，使 backend 容器内可执行 `docker exec` |
| `scanner/engine/SubfinderScanner.java` | 修改 | 改为 `docker exec secops-subfinder subfinder ...` |
| `scanner/engine/NaabuScanner.java` | 修改 | 改为 `docker exec secops-naabu naabu ...`，追加 `-rate 1000` |
| `scanner/engine/HttpxScanner.java` | 修改 | 改为 `docker exec secops-httpx httpx ...` |
| `scanner/engine/NucleiScanner.java` | 修改 | 改为 `docker exec secops-nuclei nuclei ...`，输出文件放到共享 volume |
| `controller/ScanProgressWebSocketHandler.java` | 创建 | 处理 `/ws/scans` 连接，广播扫描进度消息 |
| `config/WebSocketConfig.java` | 修改 | 注册 `/ws/scans` 端点 |
| `service/ScannerEngineService.java` | 修改 | 拆分 4 种扫描类型、WebSocket 推送、Semaphore 限流 |
| `service/ScanTaskService.java` | 修改 | 根据 `scanType` 路由到对应引擎方法 |
| `frontend/src/pages/ScanTasks.jsx` | 修改 | WebSocket 接入 `/ws/scans`、增加扫描类型选项 |

---

### Task 1: Docker Compose 容器化基础设施

**Files:**
- Modify: `docker-compose.yml`
- Modify: `backend/Dockerfile`

- [ ] **Step 1: 修改 docker-compose.yml**

添加扫描引擎服务、共享 volume `scan-workspace`、 networks `secops-net`，并更新 backend 服务挂载 docker.sock 和共享 volume：

```yaml
version: "3.8"

services:
  postgres:
    image: postgres:16-alpine
    container_name: secops-postgres
    environment:
      POSTGRES_DB: secops
      POSTGRES_USER: secops
      POSTGRES_PASSWORD: secops123
    ports:
      - "5432:5432"
    volumes:
      - postgres_data:/var/lib/postgresql/data
    networks:
      - secops-net

  redis:
    image: redis:7-alpine
    container_name: secops-redis
    ports:
      - "6379:6379"
    networks:
      - secops-net

  backend:
    build: ./backend
    container_name: secops-backend
    ports:
      - "8080:8080"
    environment:
      SPRING_DATASOURCE_URL: jdbc:postgresql://postgres:5432/secops
      SPRING_DATASOURCE_USERNAME: secops
      SPRING_DATASOURCE_PASSWORD: secops123
      SPRING_REDIS_HOST: redis
      SPRING_REDIS_PORT: 6379
    volumes:
      - /var/run/docker.sock:/var/run/docker.sock
      - scan-workspace:/workspace
    depends_on:
      - postgres
      - redis
    networks:
      - secops-net

  frontend:
    build: ./frontend
    container_name: secops-frontend
    ports:
      - "3000:80"
    depends_on:
      - backend
    networks:
      - secops-net

  nuclei:
    image: projectdiscovery/nuclei:latest
    container_name: secops-nuclei
    volumes:
      - nuclei-templates:/root/nuclei-templates
      - scan-workspace:/workspace
    command: ["sh", "-c", "nuclei -ut && sleep infinity"]
    networks:
      - secops-net

  subfinder:
    image: projectdiscovery/subfinder:latest
    container_name: secops-subfinder
    volumes:
      - scan-workspace:/workspace
    networks:
      - secops-net

  naabu:
    image: projectdiscovery/naabu:latest
    container_name: secops-naabu
    volumes:
      - scan-workspace:/workspace
    networks:
      - secops-net

  httpx:
    image: projectdiscovery/httpx:latest
    container_name: secops-httpx
    volumes:
      - scan-workspace:/workspace
    networks:
      - secops-net

volumes:
  postgres_data:
  nuclei-templates:
  scan-workspace:

networks:
  secops-net:
    driver: bridge
```

- [ ] **Step 2: 修改 backend Dockerfile 安装 docker-cli**

在 `backend/Dockerfile` 的 `FROM eclipse-temurin:17-jre-alpine` 阶段添加：

```dockerfile
FROM eclipse-temurin:17-jdk-alpine AS builder
WORKDIR /app
COPY pom.xml .
COPY src ./src
RUN apk add --no-cache maven && \
    mvn clean package -DskipTests

FROM eclipse-temurin:17-jre-alpine
WORKDIR /app
COPY --from=builder /app/target/*.jar app.jar

# 安装 docker-cli，使 backend 容器内可执行 docker exec
RUN apk add --no-cache docker-cli

# 安装扫描工具（可选，生产环境建议单独扫描节点）
RUN apk add --no-cache nmap nmap-scripts

EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: Commit**

```bash
git add docker-compose.yml backend/Dockerfile
git commit -m "build(scanner): Docker Compose 集成扫描引擎容器 + backend 安装 docker-cli"
```

---

### Task 2: SubfinderScanner docker exec 改造

**Files:**
- Modify: `backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java`

- [ ] **Step 1: 重写 SubfinderScanner**

改为通过 `docker exec secops-subfinder subfinder ...` 调用。单目标直接传 `-d` 参数，无需共享 volume 文件。

```java
package com.secops.scanner.engine;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Subfinder 子域名扫描引擎适配器
 * 通过 docker exec 调用 secops-subfinder 容器内的 subfinder 命令
 */
@Component
public class SubfinderScanner implements ScannerEngine {

    private static final String CONTAINER_NAME = "secops-subfinder";
    private static final String BINARY = "subfinder";

    @Override
    public String getName() {
        return "subfinder";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "exec", CONTAINER_NAME, BINARY, "-version");
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<ScanResult> scan(String target, ScanOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult result = new ScanResult();
            result.setScanner(getName());
            result.setTarget(target);
            result.setStartTime(LocalDateTime.now());
            result.setSuccess(true);

            ScanOptions scanOptions = options != null ? options : new ScanOptions();

            try {
                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", CONTAINER_NAME,
                        BINARY, "-d", target, "-all"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            ScanResult.Finding f = new ScanResult.Finding();
                            f.setId(UUID.randomUUID().toString());
                            f.setName("Subdomain");
                            f.setMatched(line);
                            f.setSeverity("info");
                            findings.add(f);
                        }
                    }
                }
                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? BINARY + " exited with code " + process.exitValue() : BINARY + " timed out");
                }
                result.setFindings(findings);
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
            }
            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java
git commit -m "feat(scanner): SubfinderScanner 改为 docker exec 调用容器"
```

---

### Task 3: NaabuScanner docker exec 改造 + 限速

**Files:**
- Modify: `backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java`

- [ ] **Step 1: 重写 NaabuScanner**

改为 `docker exec` 调用，目标列表写入共享 volume `/workspace`。追加 `-rate 1000` 限速。

```java
package com.secops.scanner.engine;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Naabu 端口扫描引擎适配器
 * 通过 docker exec 调用 secops-naabu 容器内的 naabu 命令
 */
@Component
public class NaabuScanner implements ScannerEngine {

    private static final String CONTAINER_NAME = "secops-naabu";
    private static final String BINARY = "naabu";
    private static final String WORKSPACE = "/workspace";

    @Override
    public String getName() {
        return "naabu";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "exec", CONTAINER_NAME, BINARY, "-version");
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<ScanResult> scan(String target, ScanOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult result = new ScanResult();
            result.setScanner(getName());
            result.setTarget(target);
            result.setStartTime(LocalDateTime.now());
            result.setSuccess(true);

            ScanOptions scanOptions = options != null ? options : new ScanOptions();
            String targetFileName = "naabu-targets-" + UUID.randomUUID() + ".txt";
            Path targetFile = Paths.get(WORKSPACE, targetFileName);

            try {
                Files.writeString(targetFile, target);

                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", CONTAINER_NAME,
                        BINARY, "-list", targetFile.toString(), "-p", "-", "-rate", "1000"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            ScanResult.Finding f = new ScanResult.Finding();
                            f.setId(UUID.randomUUID().toString());
                            f.setName("Open Port");
                            f.setMatched(line);
                            f.setSeverity("info");
                            findings.add(f);
                        }
                    }
                }

                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? BINARY + " exited with code " + process.exitValue() : BINARY + " timed out");
                }
                result.setFindings(findings);
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(targetFile);
                } catch (Exception ignored) {
                }
            }

            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java
git commit -m "feat(scanner): NaabuScanner 改为 docker exec 调用，追加 -rate 1000 限速"
```

---

### Task 4: HttpxScanner docker exec 改造

**Files:**
- Modify: `backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java`

- [ ] **Step 1: 重写 HttpxScanner**

与 NaabuScanner 模式相同，目标列表写入共享 volume。

```java
package com.secops.scanner.engine;

import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Httpx 存活探测引擎适配器
 * 通过 docker exec 调用 secops-httpx 容器内的 httpx 命令
 */
@Component
public class HttpxScanner implements ScannerEngine {

    private static final String CONTAINER_NAME = "secops-httpx";
    private static final String BINARY = "httpx";
    private static final String WORKSPACE = "/workspace";

    @Override
    public String getName() {
        return "httpx";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "exec", CONTAINER_NAME, BINARY, "-version");
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<ScanResult> scan(String target, ScanOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult result = new ScanResult();
            result.setScanner(getName());
            result.setTarget(target);
            result.setStartTime(LocalDateTime.now());
            result.setSuccess(true);

            ScanOptions scanOptions = options != null ? options : new ScanOptions();
            String targetFileName = "httpx-targets-" + UUID.randomUUID() + ".txt";
            Path targetFile = Paths.get(WORKSPACE, targetFileName);

            try {
                Files.writeString(targetFile, target);

                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", CONTAINER_NAME,
                        BINARY, "-list", targetFile.toString(), "-silent"
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            ScanResult.Finding f = new ScanResult.Finding();
                            f.setId(UUID.randomUUID().toString());
                            f.setName("Alive Host");
                            f.setMatched(line);
                            f.setSeverity("info");
                            findings.add(f);
                        }
                    }
                }

                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? BINARY + " exited with code " + process.exitValue() : BINARY + " timed out");
                }
                result.setFindings(findings);
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(targetFile);
                } catch (Exception ignored) {
                }
            }

            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java
git commit -m "feat(scanner): HttpxScanner 改为 docker exec 调用容器"
```

---

### Task 5: NucleiScanner docker exec 改造 + 限速

**Files:**
- Modify: `backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java`

- [ ] **Step 1: 重写 NucleiScanner**

改为 `docker exec` 调用，目标列表和 JSONL 输出文件都放到共享 volume `/workspace`。保留 `-rl 150` 限速。

```java
package com.secops.scanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Nuclei 漏洞扫描引擎适配器
 * 通过 docker exec 调用 secops-nuclei 容器内的 nuclei 命令
 */
@Component
public class NucleiScanner implements ScannerEngine {

    private static final String CONTAINER_NAME = "secops-nuclei";
    private static final String BINARY = "nuclei";
    private static final String WORKSPACE = "/workspace";

    @Override
    public String getName() {
        return "nuclei";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder("docker", "exec", CONTAINER_NAME, BINARY, "-version");
            Process p = pb.start();
            boolean ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
            p.destroyForcibly();
            return ok;
        } catch (Exception e) {
            return false;
        }
    }

    @Override
    public CompletableFuture<ScanResult> scan(String target, ScanOptions options) {
        return CompletableFuture.supplyAsync(() -> {
            ScanResult result = new ScanResult();
            result.setScanner(getName());
            result.setTarget(target);
            result.setStartTime(LocalDateTime.now());
            result.setSuccess(true);

            ScanOptions scanOptions = options != null ? options : new ScanOptions();
            String uuid = UUID.randomUUID().toString();
            Path targetFile = Paths.get(WORKSPACE, "nuclei-targets-" + uuid + ".txt");
            Path jsonFile = Paths.get(WORKSPACE, "nuclei-output-" + uuid + ".jsonl");

            try {
                Files.writeString(targetFile, target);

                ProcessBuilder pb = new ProcessBuilder(
                        "docker", "exec", CONTAINER_NAME,
                        BINARY,
                        "-list", targetFile.toString(),
                        "-jsonl", "-o", jsonFile.toString(),
                        "-rl", "150",
                        "-timeout", String.valueOf(scanOptions.getTimeout())
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();

                // 消费 stdout 防止缓冲区满
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                    while (reader.readLine() != null) {
                    }
                }

                boolean finished = process.waitFor(scanOptions.getTimeout(), TimeUnit.SECONDS);
                if (!finished || process.exitValue() != 0) {
                    result.setSuccess(false);
                    result.setErrorMessage(finished ? BINARY + " exited with code " + process.exitValue() : BINARY + " timed out");
                }

                ObjectMapper mapper = new ObjectMapper();
                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                StringBuilder rawOutput = new StringBuilder();

                if (Files.exists(jsonFile)) {
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(jsonFile), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            rawOutput.append(line).append("\n");
                            try {
                                JsonNode node = mapper.readTree(line);
                                ScanResult.Finding f = new ScanResult.Finding();
                                f.setId(node.has("template-id") ? node.get("template-id").asText() : UUID.randomUUID().toString());
                                f.setName(node.has("info") && node.get("info").has("name") ? node.get("info").get("name").asText() : "Unknown");
                                f.setSeverity(node.has("info") && node.get("info").has("severity") ? node.get("info").get("severity").asText() : "info");
                                f.setDescription(node.has("info") && node.get("info").has("description") ? node.get("info").get("description").asText() : "");
                                f.setMatched(node.has("matched-at") ? node.get("matched-at").asText() : "");
                                f.setMetadata(new HashMap<>());
                                f.getMetadata().put("template", node.has("template") ? node.get("template").asText() : "");
                                findings.add(f);
                            } catch (Exception e) {
                                // 跳过解析失败的行
                            }
                        }
                    }
                }

                result.setFindings(findings);
                result.setRawOutput(java.util.Map.of("jsonLines", rawOutput.toString()));
            } catch (Exception e) {
                result.setSuccess(false);
                result.setErrorMessage(e.getMessage());
            } finally {
                try {
                    Files.deleteIfExists(targetFile);
                } catch (Exception ignored) {
                }
                try {
                    Files.deleteIfExists(jsonFile);
                } catch (Exception ignored) {
                }
            }

            result.setEndTime(LocalDateTime.now());
            return result;
        });
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java
git commit -m "feat(scanner): NucleiScanner 改为 docker exec 调用，-rl 150 限速"
```

---

### Task 6: 扫描进度 WebSocket 处理器

**Files:**
- Create: `backend/src/main/java/com/secops/controller/ScanProgressWebSocketHandler.java`
- Modify: `backend/src/main/java/com/secops/config/WebSocketConfig.java`

- [ ] **Step 1: 创建 ScanProgressWebSocketHandler**

```java
package com.secops.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 扫描进度 WebSocket 处理器
 * 广播扫描任务进度到所有已连接的客户端
 */
@Slf4j
@Component
public class ScanProgressWebSocketHandler extends TextWebSocketHandler {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        log.info("扫描进度 WebSocket 连接建立: {}", session.getId());
        sessions.add(session);
        sendEvent(session, "connected", Map.of("message", "扫描进度推送已连接"));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        // 前端不需要发送消息，纯推送通道
        log.debug("收到扫描进度通道消息: {}", message.getPayload());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
        log.info("扫描进度 WebSocket 连接关闭: {}, status={}", session.getId(), status);
        sessions.remove(session);
    }

    /**
     * 广播扫描进度事件到所有连接的客户端
     */
    public void broadcastProgress(String taskId, String status, int progress, String stage, String message) {
        Map<String, Object> event = new HashMap<>();
        event.put("type", "SCAN_PROGRESS");
        event.put("taskId", taskId);
        event.put("status", status);
        event.put("progress", progress);
        event.put("stage", stage);
        event.put("message", message);

        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (IOException e) {
            log.error("序列化扫描进度事件失败", e);
            return;
        }

        for (WebSocketSession session : sessions) {
            if (session.isOpen()) {
                try {
                    session.sendMessage(new TextMessage(payload));
                } catch (IOException e) {
                    log.error("向会话 {} 发送扫描进度失败", session.getId(), e);
                }
            }
        }
    }

    private void sendEvent(WebSocketSession session, String type, Map<String, Object> data) {
        if (!session.isOpen()) return;
        try {
            Map<String, Object> event = new HashMap<>();
            event.put("type", type);
            event.put("data", data);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(event)));
        } catch (IOException e) {
            log.error("发送 WebSocket 消息失败", e);
        }
    }
}
```

- [ ] **Step 2: 修改 WebSocketConfig 注册 /ws/scans**

```java
package com.secops.config;

import com.secops.controller.AgentWebSocketHandler;
import com.secops.controller.ScanProgressWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;
    private final ScanProgressWebSocketHandler scanProgressWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler,
                           ScanProgressWebSocketHandler scanProgressWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
        this.scanProgressWebSocketHandler = scanProgressWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent")
                .setAllowedOrigins("*");
        registry.addHandler(scanProgressWebSocketHandler, "/ws/scans")
                .setAllowedOrigins("*");
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/controller/ScanProgressWebSocketHandler.java backend/src/main/java/com/secops/config/WebSocketConfig.java
git commit -m "feat(scanner): 新增扫描进度 WebSocket 推送端点 /ws/scans"
```

---

### Task 7: ScannerEngineService 扫描类型拆分 + WebSocket + 限流

**Files:**
- Modify: `backend/src/main/java/com/secops/service/ScannerEngineService.java`
- Create: `backend/src/test/java/com/secops/service/ScannerEngineServiceTest.java`

- [ ] **Step 1: 写失败测试**

创建 `backend/src/test/java/com/secops/service/ScannerEngineServiceTest.java`：

```java
package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Target;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import com.secops.entity.enums.TargetType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import com.secops.scanner.engine.*;
import com.secops.controller.ScanProgressWebSocketHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScannerEngineServiceTest {

    @Mock SubfinderScanner subfinderScanner;
    @Mock NaabuScanner naabuScanner;
    @Mock HttpxScanner httpxScanner;
    @Mock NucleiScanner nucleiScanner;
    @Mock ScanTaskRepository scanTaskRepository;
    @Mock VulnerabilityRepository vulnerabilityRepository;
    @Mock ScanProgressWebSocketHandler webSocketHandler;

    @InjectMocks
    ScannerEngineService scannerEngineService;

    private ScanTask createTask(ScanType type) {
        Target target = new Target();
        target.setDomain("example.com");
        target.setType(TargetType.DOMAIN);

        ScanTask task = new ScanTask();
        task.setTaskId("SCAN-20260609-0001");
        task.setTarget(target);
        task.setScanType(type);
        task.setStatus(ScanStatus.PENDING);
        return task;
    }

    @BeforeEach
    void setup() {
        ScanResult emptyResult = new ScanResult();
        emptyResult.setSuccess(true);
        emptyResult.setFindings(new java.util.ArrayList<>());

        when(subfinderScanner.isAvailable()).thenReturn(true);
        when(subfinderScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
        when(naabuScanner.isAvailable()).thenReturn(true);
        when(naabuScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
        when(httpxScanner.isAvailable()).thenReturn(true);
        when(httpxScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
        when(nucleiScanner.isAvailable()).thenReturn(true);
        when(nucleiScanner.scan(any(), any())).thenReturn(CompletableFuture.completedFuture(emptyResult));
    }

    @Test
    void runFullScan_shouldExecuteAllStages() {
        ScanTask task = createTask(ScanType.FULL);
        scannerEngineService.runFullScan(task);

        verify(subfinderScanner, atLeastOnce()).scan(any(), any());
        verify(naabuScanner, atLeastOnce()).scan(any(), any());
        verify(httpxScanner, atLeastOnce()).scan(any(), any());
        verify(nucleiScanner, atLeastOnce()).scan(any(), any());

        ArgumentCaptor<ScanTask> captor = ArgumentCaptor.forClass(ScanTask.class);
        verify(scanTaskRepository, atLeastOnce()).save(captor.capture());
        assertEquals(ScanStatus.COMPLETED, captor.getValue().getStatus());
        assertEquals(100, captor.getValue().getProgress());
    }

    @Test
    void runSubdomainScan_shouldExecuteOnlySubfinder() {
        ScanTask task = createTask(ScanType.SUBDOMAIN);
        scannerEngineService.runSubdomainScan(task);

        verify(subfinderScanner, atLeastOnce()).scan(any(), any());
        verify(naabuScanner, never()).scan(any(), any());
        verify(httpxScanner, never()).scan(any(), any());
        verify(nucleiScanner, never()).scan(any(), any());
    }

    @Test
    void runPortScan_shouldExecuteOnlyNaabu() {
        ScanTask task = createTask(ScanType.PORT);
        scannerEngineService.runPortScan(task);

        verify(subfinderScanner, never()).scan(any(), any());
        verify(naabuScanner, atLeastOnce()).scan(any(), any());
        verify(httpxScanner, never()).scan(any(), any());
        verify(nucleiScanner, never()).scan(any(), any());
    }

    @Test
    void runVulnScan_shouldExecuteOnlyNuclei() {
        ScanTask task = createTask(ScanType.VULN);
        scannerEngineService.runVulnScan(task);

        verify(subfinderScanner, never()).scan(any(), any());
        verify(naabuScanner, never()).scan(any(), any());
        verify(httpxScanner, never()).scan(any(), any());
        verify(nucleiScanner, atLeastOnce()).scan(any(), any());
    }

    @Test
    void runFullScan_shouldBroadcastProgressEvents() {
        ScanTask task = createTask(ScanType.FULL);
        scannerEngineService.runFullScan(task);

        verify(webSocketHandler, atLeastOnce()).broadcastProgress(
                eq("SCAN-20260609-0001"), any(), anyInt(), any(), any()
        );
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

```bash
cd backend
mvn test -Dtest=ScannerEngineServiceTest -q
```

Expected: 编译失败或测试失败，因为 `ScannerEngineService` 缺少 `runSubdomainScan`、`runPortScan`、`runVulnScan` 方法和 `ScanProgressWebSocketHandler` 注入。

- [ ] **Step 3: 重写 ScannerEngineService**

完整替换 `backend/src/main/java/com/secops/service/ScannerEngineService.java`：

```java
package com.secops.service;

import com.secops.controller.ScanProgressWebSocketHandler;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.TargetType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import com.secops.scanner.engine.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Semaphore;
import java.util.stream.Collectors;

/**
 * 扫描引擎编排服务
 * 负责执行扫描流水线，支持 FULL/SUBDOMAIN/PORT/VULN 四种类型
 * 当工具不可用时自动降级为 Java 原生探测
 */
@Service
public class ScannerEngineService {

    private final SubfinderScanner subfinderScanner;
    private final NaabuScanner naabuScanner;
    private final HttpxScanner httpxScanner;
    private final NucleiScanner nucleiScanner;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final ScanProgressWebSocketHandler webSocketHandler;

    // 全局并发控制：最多同时执行 3 个扫描任务
    private final Semaphore scanSemaphore = new Semaphore(3);

    public ScannerEngineService(SubfinderScanner subfinderScanner, NaabuScanner naabuScanner,
                                HttpxScanner httpxScanner, NucleiScanner nucleiScanner,
                                ScanTaskRepository scanTaskRepository,
                                VulnerabilityRepository vulnerabilityRepository,
                                ScanProgressWebSocketHandler webSocketHandler) {
        this.subfinderScanner = subfinderScanner;
        this.naabuScanner = naabuScanner;
        this.httpxScanner = httpxScanner;
        this.nucleiScanner = nucleiScanner;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.webSocketHandler = webSocketHandler;
    }

    // ========== 四种扫描入口 ==========

    @Async
    public void runFullScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            String domain = task.getTarget().getDomain();
            boolean isIp = task.getTarget().getType() == TargetType.IP;
            StringBuilder rawOutput = new StringBuilder();

            String subdomains = domain;

            // Step 1: Subfinder
            if (!isIp) {
                subdomains = runSubfinderStage(task, rawOutput);
            } else {
                pushProgress(task, "SUBDOMAIN_SCAN", "IP 目标跳过子域名发现", 30);
                rawOutput.append("Subfinder: skipped for IP target\n");
                saveTask(task, rawOutput.toString());
            }

            // Step 2: Naabu
            runNaabuStage(task, subdomains, rawOutput);

            // Step 3: Httpx
            String aliveUrls = runHttpxStage(task, subdomains, rawOutput);

            // Step 4: Nuclei
            runNucleiStage(task, aliveUrls, rawOutput);

            completeTask(task, rawOutput.toString());
        });
    }

    @Async
    public void runSubdomainScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            StringBuilder rawOutput = new StringBuilder();
            runSubfinderStage(task, rawOutput);
            completeTask(task, rawOutput.toString());
        });
    }

    @Async
    public void runPortScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            String domain = task.getTarget().getDomain();
            StringBuilder rawOutput = new StringBuilder();
            runNaabuStage(task, domain, rawOutput);
            completeTask(task, rawOutput.toString());
        });
    }

    @Async
    public void runVulnScan(ScanTask task) {
        executeWithSemaphore(task, () -> {
            String domain = task.getTarget().getDomain();
            StringBuilder rawOutput = new StringBuilder();
            runNucleiStage(task, domain, rawOutput);
            completeTask(task, rawOutput.toString());
        });
    }

    // ========== 阶段执行方法 ==========

    private String runSubfinderStage(ScanTask task, StringBuilder rawOutput) {
        String domain = task.getTarget().getDomain();
        String subdomains = domain;

        if (subfinderScanner.isAvailable()) {
            try {
                ScanResult subResult = subfinderScanner.scan(domain, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "SUBDOMAIN_SCAN", "Subfinder: " + subResult.getFindings().size() + " subdomains", 30);
                rawOutput.append("Subfinder: ").append(subResult.getFindings().size()).append(" subdomains\n");
                saveTask(task, rawOutput.toString());

                subdomains = subResult.getFindings().stream()
                        .map(ScanResult.Finding::getMatched)
                        .collect(Collectors.joining("\n"));
                if (subdomains.isEmpty()) subdomains = domain;
            } catch (Exception e) {
                pushProgress(task, "SUBDOMAIN_SCAN", "Subfinder 执行失败: " + e.getMessage(), 30);
                rawOutput.append("Subfinder: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "SUBDOMAIN_SCAN", "Subfinder 不可用，使用 DNS 降级探测", 30);
            rawOutput.append("Subfinder: 工具不可用，使用 DNS 降级探测\n");
            List<String> found = probeSubdomains(domain);
            rawOutput.append("DNS probe: ").append(found.size()).append(" subdomains\n");
            saveTask(task, rawOutput.toString());
            subdomains = found.isEmpty() ? domain : String.join("\n", found);
        }
        return subdomains;
    }

    private void runNaabuStage(ScanTask task, String targets, StringBuilder rawOutput) {
        if (naabuScanner.isAvailable()) {
            try {
                ScanResult portResult = naabuScanner.scan(targets, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "PORT_SCAN", "Naabu: " + portResult.getFindings().size() + " ports", 50);
                rawOutput.append("Naabu: ").append(portResult.getFindings().size()).append(" ports\n");
                saveTask(task, rawOutput.toString());
            } catch (Exception e) {
                pushProgress(task, "PORT_SCAN", "Naabu 执行失败: " + e.getMessage(), 50);
                rawOutput.append("Naabu: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "PORT_SCAN", "Naabu 不可用，使用 Socket 降级探测", 50);
            rawOutput.append("Naabu: 工具不可用，使用 Socket 降级探测\n");
            List<String> found = probePorts(targets);
            rawOutput.append("Socket probe: ").append(found.size()).append(" open ports\n");
            saveTask(task, rawOutput.toString());
        }
    }

    private String runHttpxStage(ScanTask task, String targets, StringBuilder rawOutput) {
        String domain = task.getTarget().getDomain();
        String aliveUrls = domain;

        if (httpxScanner.isAvailable()) {
            try {
                ScanResult aliveResult = httpxScanner.scan(targets, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "HTTP_PROBE", "Httpx: " + aliveResult.getFindings().size() + " alive hosts", 70);
                rawOutput.append("Httpx: ").append(aliveResult.getFindings().size()).append(" alive hosts\n");
                saveTask(task, rawOutput.toString());

                aliveUrls = aliveResult.getFindings().stream()
                        .map(ScanResult.Finding::getMatched)
                        .collect(Collectors.joining("\n"));
                if (aliveUrls.isEmpty()) aliveUrls = domain;
            } catch (Exception e) {
                pushProgress(task, "HTTP_PROBE", "Httpx 执行失败: " + e.getMessage(), 70);
                rawOutput.append("Httpx: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "HTTP_PROBE", "Httpx 不可用，使用 HTTP 降级探测", 70);
            rawOutput.append("Httpx: 工具不可用，使用 HTTP 降级探测\n");
            List<String> found = probeHttpAlive(targets);
            rawOutput.append("HTTP probe: ").append(found.size()).append(" alive hosts\n");
            saveTask(task, rawOutput.toString());
            aliveUrls = found.isEmpty() ? domain : String.join("\n", found);
        }
        return aliveUrls;
    }

    private void runNucleiStage(ScanTask task, String targets, StringBuilder rawOutput) {
        if (nucleiScanner.isAvailable()) {
            try {
                ScanResult vulnResult = nucleiScanner.scan(targets, new ScannerEngine.ScanOptions()).get();
                pushProgress(task, "VULN_SCAN", "Nuclei: " + vulnResult.getFindings().size() + " findings", 90);
                rawOutput.append("Nuclei: ").append(vulnResult.getFindings().size()).append(" findings\n");
                saveTask(task, rawOutput.toString());

                for (ScanResult.Finding finding : vulnResult.getFindings()) {
                    Vulnerability v = new Vulnerability();
                    v.setName(finding.getName());
                    v.setSeverity(parseSeverity(finding.getSeverity()));
                    v.setDescription(finding.getDescription());
                    v.setMatched(finding.getMatched());
                    v.setTarget(task.getTarget().getDomain());
                    v.setScanner("nuclei");
                    v.setScanTask(task);
                    vulnerabilityRepository.save(v);
                }
            } catch (Exception e) {
                pushProgress(task, "VULN_SCAN", "Nuclei 执行失败: " + e.getMessage(), 90);
                rawOutput.append("Nuclei: error ").append(e.getMessage()).append("\n");
                saveTask(task, rawOutput.toString());
            }
        } else {
            pushProgress(task, "VULN_SCAN", "Nuclei 不可用，使用 HTTP 降级漏洞探测", 90);
            rawOutput.append("Nuclei: 工具不可用，使用 HTTP 降级漏洞探测\n");
            List<ScanResult.Finding> findings = probeVulnerabilities(targets);
            rawOutput.append("HTTP vuln probe: ").append(findings.size()).append(" findings\n");
            saveTask(task, rawOutput.toString());

            for (ScanResult.Finding finding : findings) {
                Vulnerability v = new Vulnerability();
                v.setName(finding.getName());
                v.setSeverity(parseSeverity(finding.getSeverity()));
                v.setDescription(finding.getDescription());
                v.setMatched(finding.getMatched());
                v.setTarget(task.getTarget().getDomain());
                v.setScanner("http-probe");
                v.setScanTask(task);
                vulnerabilityRepository.save(v);
            }
        }
    }

    // ========== 任务生命周期 ==========

    private void executeWithSemaphore(ScanTask task, Runnable scanLogic) {
        boolean acquired = false;
        try {
            if (!scanSemaphore.tryAcquire()) {
                task.setStatus(ScanStatus.QUEUED);
                scanTaskRepository.save(task);
                scanSemaphore.acquire();
            }
            acquired = true;

            task.setStatus(ScanStatus.RUNNING);
            task.setStartTime(LocalDateTime.now());
            task.setProgress(10);
            scanTaskRepository.save(task);

            scanLogic.run();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            failTask(task, "扫描被中断");
        } catch (Exception e) {
            failTask(task, e.getMessage());
        } finally {
            if (acquired) {
                scanSemaphore.release();
            }
        }
    }

    private void completeTask(ScanTask task, String rawOutput) {
        task.setStatus(ScanStatus.COMPLETED);
        task.setProgress(100);
        task.setEndTime(LocalDateTime.now());
        task.setRawOutput(rawOutput);
        scanTaskRepository.save(task);
        pushProgress(task, "COMPLETED", "扫描完成", 100);
    }

    private void failTask(ScanTask task, String error) {
        task.setStatus(ScanStatus.FAILED);
        task.setErrorMessage(error);
        task.setEndTime(LocalDateTime.now());
        scanTaskRepository.save(task);
        pushProgress(task, "FAILED", "扫描失败: " + error, task.getProgress());
    }

    private void saveTask(ScanTask task, String rawOutput) {
        task.setRawOutput(rawOutput);
        scanTaskRepository.save(task);
    }

    private void pushProgress(ScanTask task, String stage, String message, int progress) {
        task.setProgress(progress);
        webSocketHandler.broadcastProgress(
                task.getTaskId(),
                task.getStatus().name(),
                progress,
                stage,
                message
        );
    }

    // ========== 降级探测方法（保持不变）==========

    private List<String> probeSubdomains(String domain) {
        String[] common = {"www", "api", "mail", "blog", "dev", "test", "staging", "admin"};
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String prefix : common) {
            String sub = prefix + "." + domain;
            try {
                InetAddress.getByName(sub);
                found.add(sub);
            } catch (Exception ignored) {
            }
        }
        return found;
    }

    private List<String> probePorts(String targets) {
        int[] commonPorts = {80, 443, 8080, 8443, 22, 3306, 5432, 6379};
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String host : targets.split("\n")) {
            host = host.trim();
            if (host.isEmpty()) continue;
            for (int port : commonPorts) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(host, port), 2000);
                    found.add(host + ":" + port);
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }

    private List<String> probeHttpAlive(String targets) {
        java.util.List<String> found = new java.util.ArrayList<>();
        for (String host : targets.split("\n")) {
            host = host.trim();
            if (host.isEmpty()) continue;
            for (String scheme : new String[]{"http", "https"}) {
                try {
                    URL url = new URL(scheme + "://" + host);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestMethod("HEAD");
                    int code = conn.getResponseCode();
                    if (code > 0) {
                        found.add(url.toString());
                        break;
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return found;
    }

    private List<ScanResult.Finding> probeVulnerabilities(String urls) {
        java.util.List<ScanResult.Finding> findings = new java.util.ArrayList<>();
        String[] probes = {
            "/.git/config|Git Configuration Exposure|HIGH|Git configuration file was exposed, potentially leaking repository metadata and credentials.",
            "/.env|Environment Variable File Exposure|CRITICAL|Environment file (.env) was exposed, containing sensitive credentials and configuration.",
            "/admin|Unauthenticated Admin Panel|HIGH|Admin panel is accessible without authentication.",
            "/actuator/env|Spring Boot Actuator Exposed|HIGH|Spring Boot Actuator endpoint exposed, potentially leaking environment variables.",
            "/.htaccess|Apache .htaccess Exposure|MEDIUM|Apache configuration file exposed.",
            "/robots.txt|Robots.txt Exposure|INFO|robots.txt may reveal hidden paths.",
        };

        for (String baseUrl : urls.split("\n")) {
            baseUrl = baseUrl.trim();
            if (baseUrl.isEmpty()) continue;
            for (String probe : probes) {
                String[] parts = probe.split("\\|", 4);
                String path = parts[0];
                String name = parts[1];
                String severity = parts[2];
                String description = parts[3];
                try {
                    URL url = new URL(baseUrl + path);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setConnectTimeout(3000);
                    conn.setReadTimeout(3000);
                    conn.setRequestMethod("GET");
                    conn.setInstanceFollowRedirects(false);
                    int code = conn.getResponseCode();
                    if (code == 200) {
                        ScanResult.Finding f = new ScanResult.Finding();
                        f.setId(java.util.UUID.randomUUID().toString());
                        f.setName(name);
                        f.setSeverity(severity);
                        f.setDescription(description);
                        f.setMatched(url.toString());
                        findings.add(f);
                    }
                } catch (Exception ignored) {
                }
            }
        }
        return findings;
    }

    private Severity parseSeverity(String severity) {
        return switch (severity != null ? severity.toLowerCase() : "info") {
            case "critical" -> Severity.CRITICAL;
            case "high" -> Severity.HIGH;
            case "medium" -> Severity.MEDIUM;
            case "low" -> Severity.LOW;
            default -> Severity.INFO;
        };
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

```bash
cd backend
mvn test -Dtest=ScannerEngineServiceTest -q
```

Expected: `Tests run: 5, Failures: 0, Errors: 0`

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/secops/service/ScannerEngineService.java backend/src/test/java/com/secops/service/ScannerEngineServiceTest.java
git commit -m "feat(scanner): ScannerEngineService 拆分扫描类型、WebSocket 推送、Semaphore 限流"
```

---

### Task 8: ScanTaskService 扫描类型路由

**Files:**
- Modify: `backend/src/main/java/com/secops/service/ScanTaskService.java`

- [ ] **Step 1: 修改 ScanTaskService**

根据 `scanType` 路由到对应引擎方法：

```java
package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Target;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.TargetRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * 扫描任务业务服务
 */
@Service
public class ScanTaskService {

    private final ScanTaskRepository scanTaskRepository;
    private final TargetRepository targetRepository;
    private final ScannerEngineService scannerEngineService;

    public ScanTaskService(ScanTaskRepository scanTaskRepository, TargetRepository targetRepository,
                           ScannerEngineService scannerEngineService) {
        this.scanTaskRepository = scanTaskRepository;
        this.targetRepository = targetRepository;
        this.scannerEngineService = scannerEngineService;
    }

    public ScanTask createScanTask(Long targetId, ScanType scanType) {
        Target target = targetRepository.findById(targetId)
                .orElseThrow(() -> new IllegalArgumentException("目标不存在"));

        ScanTask task = new ScanTask();
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        task.setTaskId("SCAN-" + dateStr + "-" + String.format("%04d", System.currentTimeMillis() % 10000));
        task.setTarget(target);
        task.setScanType(scanType);
        task.setStatus(ScanStatus.PENDING);
        scanTaskRepository.save(task);

        switch (scanType) {
            case FULL -> scannerEngineService.runFullScan(task);
            case SUBDOMAIN -> scannerEngineService.runSubdomainScan(task);
            case PORT -> scannerEngineService.runPortScan(task);
            case VULN -> scannerEngineService.runVulnScan(task);
        }
        return task;
    }

    public List<ScanTask> listAll() {
        return scanTaskRepository.findAll();
    }

    public ScanTask getTask(Long id) {
        return scanTaskRepository.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("任务不存在"));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/service/ScanTaskService.java
git commit -m "feat(scanner): ScanTaskService 根据 scanType 路由到对应引擎方法"
```

---

### Task 9: 前端 ScanTasks.jsx WebSocket + 扫描类型

**Files:**
- Modify: `frontend/src/pages/ScanTasks.jsx`

- [ ] **Step 1: 重写 ScanTasks.jsx**

增加 `/ws/scans` WebSocket 连接、扫描类型选项（SUBDOMAIN/PORT/VULN）、移除轮询（保留为降级）。

```jsx
import React, { useState, useEffect, useRef } from 'react'
import { Card, Button, Table, Tag, Progress, Space, Modal, Form, Select, message, Typography } from 'antd'
import { PlayCircleOutlined, FileTextOutlined } from '@ant-design/icons'
import client from '../api/client'

const { Paragraph } = Typography

const statusMap = {
  'PENDING': { text: '等待中', color: 'default' },
  'QUEUED': { text: '队列中', color: 'orange' },
  'RUNNING': { text: '运行中', color: 'blue' },
  'COMPLETED': { text: '完成', color: 'green' },
  'FAILED': { text: '失败', color: 'red' },
}

const stageMap = {
  'SUBDOMAIN_SCAN': '子域名发现',
  'PORT_SCAN': '端口扫描',
  'HTTP_PROBE': '存活探测',
  'VULN_SCAN': '漏洞扫描',
  'COMPLETED': '扫描完成',
  'FAILED': '执行失败',
}

const WS_URL = `${window.location.protocol === 'https:' ? 'wss' : 'ws'}://${window.location.host}/ws/scans`

export default function ScanTasks() {
  const [data, setData] = useState([])
  const [targets, setTargets] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [logOpen, setLogOpen] = useState(false)
  const [logRecord, setLogRecord] = useState(null)
  const [form] = Form.useForm()
  const wsRef = useRef(null)

  const fetchTasks = async () => {
    setLoading(true)
    try {
      const res = await client.get('/scans')
      if (res.code === 200) {
        setData(res.data.map(t => ({ ...t, key: t.id })))
      }
    } catch (err) {
      message.error(err.message || '加载失败')
    } finally {
      setLoading(false)
    }
  }

  const fetchTargets = async () => {
    try {
      const res = await client.get('/targets')
      if (res.code === 200) {
        setTargets(res.data.filter(t => t.verified))
      }
    } catch {}
  }

  useEffect(() => {
    fetchTasks()
    fetchTargets()
  }, [])

  // WebSocket 实时进度
  useEffect(() => {
    const ws = new WebSocket(WS_URL)
    wsRef.current = ws

    ws.onopen = () => {
      console.log('扫描进度 WebSocket 已连接')
    }

    ws.onmessage = (event) => {
      try {
        const msg = JSON.parse(event.data)
        if (msg.type === 'SCAN_PROGRESS') {
          setData(prev => prev.map(t => {
            if (t.taskId === msg.taskId) {
              return {
                ...t,
                status: msg.status,
                progress: msg.progress,
                stage: msg.stage,
                stageMessage: msg.message,
              }
            }
            return t
          }))
        }
      } catch (e) {
        console.error('WebSocket 消息解析失败', e)
      }
    }

    ws.onerror = (err) => {
      console.error('扫描进度 WebSocket 错误', err)
    }

    ws.onclose = () => {
      console.log('扫描进度 WebSocket 已关闭')
    }

    return () => {
      ws.close()
    }
  }, [])

  // 降级轮询：当 WebSocket 断开时，每 5s 轮询一次
  useEffect(() => {
    const interval = setInterval(() => {
      const ws = wsRef.current
      if (!ws || ws.readyState !== WebSocket.OPEN) {
        if (data.some(d => d.status === 'RUNNING' || d.status === 'QUEUED')) {
          fetchTasks()
        }
      }
    }, 5000)
    return () => clearInterval(interval)
  }, [data])

  const handleLaunch = async (values) => {
    try {
      const res = await client.post('/scans', values)
      if (res.code === 200) {
        message.success('扫描任务已创建')
        setModalOpen(false)
        form.resetFields()
        fetchTasks()
      } else {
        message.error(res.msg || '创建失败')
      }
    } catch (err) {
      message.error(err.message || '创建失败')
    }
  }

  const handleViewLog = (record) => {
    setLogRecord(record)
    setLogOpen(true)
  }

  const columns = [
    { title: '任务ID', dataIndex: 'taskId', key: 'taskId' },
    { title: '目标', dataIndex: ['target', 'domain'], key: 'target' },
    { title: '扫描类型', dataIndex: 'scanType', key: 'scanType' },
    { title: '状态', dataIndex: 'status', key: 'status',
      render: (s, record) => (
        <Tag color={statusMap[s]?.color}>
          {statusMap[s]?.text || s}
          {record.stageMessage ? ` (${record.stageMessage})` : ''}
        </Tag>
      ) },
    { title: '进度', dataIndex: 'progress', key: 'progress',
      render: (p, record) => (
        <div>
          <Progress percent={p} size="small" />
          {record.stage && <div style={{ fontSize: 12, color: '#888' }}>{stageMap[record.stage] || record.stage}</div>}
        </div>
      ) },
    { title: '操作', key: 'action',
      render: (_, record) => (
        <Space>
          <Button type="link" icon={<FileTextOutlined />} onClick={() => handleViewLog(record)}>
            日志
          </Button>
          <a href={`/vulns?scanId=${record.id}`}>结果</a>
        </Space>
      ) },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>扫描任务</h2>
        <Button type="primary" icon={<PlayCircleOutlined />} onClick={() => setModalOpen(true)}>发起扫描</Button>
      </div>
      <Card>
        <Table columns={columns} dataSource={data} loading={loading} scroll={{ x: 'max-content' }} />
      </Card>

      <Modal title="发起扫描" open={modalOpen} onCancel={() => setModalOpen(false)} footer={null}>
        <Form form={form} onFinish={handleLaunch} layout="vertical">
          <Form.Item name="targetId" label="目标" rules={[{ required: true, message: '请选择目标' }]}>
            <Select placeholder="选择已验证的目标">
              {targets.map(t => <Select.Option key={t.id} value={t.id}>{t.domain}</Select.Option>)}
            </Select>
          </Form.Item>
          <Form.Item name="scanType" label="扫描类型" initialValue="FULL" rules={[{ required: true }]}>
            <Select>
              <Select.Option value="FULL">完整扫描（子域名 → 端口 → 存活 → 漏洞）</Select.Option>
              <Select.Option value="SUBDOMAIN">子域名发现</Select.Option>
              <Select.Option value="PORT">端口扫描</Select.Option>
              <Select.Option value="VULN">漏洞扫描</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>开始扫描</Button>
          </Form.Item>
        </Form>
      </Modal>

      <Modal
        title={`扫描日志 - ${logRecord?.taskId || ''}`}
        open={logOpen}
        onCancel={() => setLogOpen(false)}
        footer={null}
        width={700}
      >
        {logRecord && (
          <div>
            <p><strong>状态:</strong> {statusMap[logRecord.status]?.text || logRecord.status}</p>
            <p><strong>目标:</strong> {logRecord.target?.domain}</p>
            {logRecord.errorMessage && (
              <p style={{ color: '#cf1322' }}><strong>错误:</strong> {logRecord.errorMessage}</p>
            )}
            <Paragraph
              copyable
              style={{
                background: '#f6f8fa',
                padding: 12,
                borderRadius: 6,
                maxHeight: 400,
                overflow: 'auto',
                fontFamily: 'monospace',
                fontSize: 12,
                whiteSpace: 'pre-wrap',
              }}
            >
              {logRecord.rawOutput || '暂无日志'}
            </Paragraph>
          </div>
        )}
      </Modal>
    </div>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/ScanTasks.jsx
git commit -m "feat(scanner): 前端扫描任务接入 WebSocket 实时进度，增加扫描类型选项"
```

---

### Task 10: 集成验证

**Files:**
- 无新增文件

- [ ] **Step 1: 后端编译与单元测试**

```bash
cd backend
mvn clean test -q
```

Expected: `BUILD SUCCESS`，`ScannerEngineServiceTest` 通过。

- [ ] **Step 2: 检查 ScanStatus 枚举是否包含 QUEUED**

如果 `backend/src/main/java/com/secops/entity/enums/ScanStatus.java` 没有 `QUEUED`，需要添加：

```java
package com.secops.entity.enums;

public enum ScanStatus {
    PENDING, QUEUED, RUNNING, COMPLETED, FAILED
}
```

- [ ] **Step 3: 前端编译检查**

```bash
cd frontend
npm run build
```

Expected: 无编译错误。

- [ ] **Step 4: Docker Compose 配置验证**

```bash
docker compose config
```

Expected: 配置验证通过，无语法错误。

- [ ] **Step 5: Commit（如有变更）**

```bash
git add -A
git commit -m "fix(scanner): 集成验证修复（ScanStatus 枚举补充等）"
```

---

## Self-Review

**1. Spec coverage:**
- ✅ 容器化 — Task 1
- ✅ 扫描类型拆分 — Task 7（4 个入口方法）、Task 8（路由）
- ✅ WebSocket 实时推送 — Task 6（Handler + Config）、Task 7（Service 推送）、Task 9（前端接入）
- ✅ 扫描限流 — Task 7（Semaphore）

**2. Placeholder scan:**
- ✅ 无 TBD/TODO
- ✅ 每个代码步骤都有完整代码块
- ✅ 每个测试都有明确预期输出

**3. Type consistency:**
- ✅ `ScanType` 枚举值（FULL, SUBDOMAIN, PORT, VULN）前后端一致
- ✅ `ScanStatus` 新增 QUEUED，前端状态映射表同步更新
- ✅ WebSocket 消息字段（type, taskId, status, progress, stage, message）前后端一致
