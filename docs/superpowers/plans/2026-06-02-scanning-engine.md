# 扫描引擎集成模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SecOps Agent 平台集成真实扫描引擎（Subfinder/Naabu/Httpx/Nuclei），实现从目标选择到漏洞发现的完整扫描流水线。

**Architecture:** 四个扫描器适配器实现统一的 `ScannerEngine` 接口，通过 `ProcessBuilder` 调用外部工具。`ScannerEngineService` 使用 `@Async` 编排流水线扫描。前端通过 3 秒轮询获取实时进度。

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Data JPA, ProcessBuilder, React 19, Ant Design

---

## 文件结构

### 后端新增
- `backend/src/main/java/com/secops/entity/enums/ScanStatus.java`
- `backend/src/main/java/com/secops/entity/enums/ScanType.java`
- `backend/src/main/java/com/secops/entity/enums/Severity.java`
- `backend/src/main/java/com/secops/entity/enums/VulnStatus.java`
- `backend/src/main/java/com/secops/entity/ScanTask.java`
- `backend/src/main/java/com/secops/entity/Vulnerability.java`
- `backend/src/main/java/com/secops/repository/ScanTaskRepository.java`
- `backend/src/main/java/com/secops/repository/VulnerabilityRepository.java`
- `backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java`
- `backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java`
- `backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java`
- `backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java`
- `backend/src/main/java/com/secops/service/ScannerEngineService.java`
- `backend/src/main/java/com/secops/service/ScanTaskService.java`
- `backend/src/main/java/com/secops/controller/ScanTaskController.java`
- `backend/src/test/java/com/secops/controller/ScanTaskControllerTest.java`

### 后端修改
- `backend/src/main/java/com/secops/SecOpsApplication.java` — 添加 `@EnableAsync`

### 前端修改
- `frontend/src/pages/ScanTasks.jsx` — 对接真实 API，轮询进度

---

## Task 1: 枚举 + 实体 + Repository

**Files:**
- Create: `backend/src/main/java/com/secops/entity/enums/ScanStatus.java`
- Create: `backend/src/main/java/com/secops/entity/enums/ScanType.java`
- Create: `backend/src/main/java/com/secops/entity/enums/Severity.java`
- Create: `backend/src/main/java/com/secops/entity/enums/VulnStatus.java`
- Create: `backend/src/main/java/com/secops/entity/ScanTask.java`
- Create: `backend/src/main/java/com/secops/entity/Vulnerability.java`
- Create: `backend/src/main/java/com/secops/repository/ScanTaskRepository.java`
- Create: `backend/src/main/java/com/secops/repository/VulnerabilityRepository.java`

### 1.1 创建枚举

`ScanStatus.java`:
```java
package com.secops.entity.enums;

public enum ScanStatus {
    PENDING, RUNNING, COMPLETED, FAILED
}
```

`ScanType.java`:
```java
package com.secops.entity.enums;

public enum ScanType {
    FULL, SUBDOMAIN, PORT, VULN
}
```

`Severity.java`:
```java
package com.secops.entity.enums;

public enum Severity {
    CRITICAL, HIGH, MEDIUM, LOW, INFO
}
```

`VulnStatus.java`:
```java
package com.secops.entity.enums;

public enum VulnStatus {
    OPEN, FIXED, FALSE_POSITIVE
}
```

### 1.2 创建 ScanTask 实体

```java
package com.secops.entity;

import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.ScanType;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class ScanTask {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String taskId;

    @ManyToOne
    @JoinColumn(name = "target_id")
    private Target target;

    @Enumerated(EnumType.STRING)
    private ScanStatus status = ScanStatus.PENDING;

    private int progress = 0;

    @Enumerated(EnumType.STRING)
    private ScanType scanType;

    @Column(length = 50000)
    private String rawOutput;

    private LocalDateTime startTime;

    private LocalDateTime endTime;

    private String errorMessage;
}
```

### 1.3 创建 Vulnerability 实体

```java
package com.secops.entity;

import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Vulnerability {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String name;

    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(length = 2000)
    private String description;

    private String matched;

    private String target;

    private String scanner;

    private LocalDateTime foundAt = LocalDateTime.now();

    @Enumerated(EnumType.STRING)
    private VulnStatus status = VulnStatus.OPEN;

    @ManyToOne
    @JoinColumn(name = "scan_task_id")
    private ScanTask scanTask;
}
```

### 1.4 创建 Repository

`ScanTaskRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.ScanTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScanTaskRepository extends JpaRepository<ScanTask, Long> {
    List<ScanTask> findByTargetId(Long targetId);
}
```

`VulnerabilityRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.Vulnerability;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VulnerabilityRepository extends JpaRepository<Vulnerability, Long> {
    List<Vulnerability> findByScanTaskId(Long scanTaskId);
}
```

### 1.5 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 1.6 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/entity/enums/ backend/src/main/java/com/secops/entity/ScanTask.java backend/src/main/java/com/secops/entity/Vulnerability.java backend/src/main/java/com/secops/repository/ScanTaskRepository.java backend/src/main/java/com/secops/repository/VulnerabilityRepository.java
git commit -m "feat(scanner): 添加扫描任务和漏洞实体、枚举及 Repository"
```

---

## Task 2: SubfinderScanner 适配器

**Files:**
- Create: `backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java`

### 2.1 创建 SubfinderScanner

```java
package com.secops.scanner.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class SubfinderScanner implements ScannerEngine {

    @Value("${scanner.subfinder.path:/usr/local/bin/subfinder}")
    private String binaryPath;

    @Override
    public String getName() {
        return "subfinder";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-version");
            Process p = pb.start();
            return p.waitFor() == 0;
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

            try {
                ProcessBuilder pb = new ProcessBuilder(binaryPath, "-d", target, "-all");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
                process.waitFor();
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

### 2.2 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 2.3 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java
git commit -m "feat(scanner): 添加 SubfinderScanner 适配器"
```

---

## Task 3: NaabuScanner 适配器

**Files:**
- Create: `backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java`

### 3.1 创建 NaabuScanner

```java
package com.secops.scanner.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class NaabuScanner implements ScannerEngine {

    @Value("${scanner.naabu.path:/usr/local/bin/naabu}")
    private String binaryPath;

    @Override
    public String getName() {
        return "naabu";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-version");
            Process p = pb.start();
            return p.waitFor() == 0;
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

            try {
                java.io.File tempFile = java.io.File.createTempFile("naabu-input", ".txt");
                try (java.io.FileWriter w = new java.io.FileWriter(tempFile)) {
                    w.write(target);
                }

                ProcessBuilder pb = new ProcessBuilder(binaryPath, "-list", tempFile.getAbsolutePath(), "-p", "-");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
                process.waitFor();
                tempFile.delete();
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

### 3.2 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 3.3 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java
git commit -m "feat(scanner): 添加 NaabuScanner 适配器"
```

---

## Task 4: HttpxScanner 适配器

**Files:**
- Create: `backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java`

### 4.1 创建 HttpxScanner

```java
package com.secops.scanner.engine;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class HttpxScanner implements ScannerEngine {

    @Value("${scanner.httpx.path:/usr/local/bin/httpx}")
    private String binaryPath;

    @Override
    public String getName() {
        return "httpx";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-version");
            Process p = pb.start();
            return p.waitFor() == 0;
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

            try {
                java.io.File tempFile = java.io.File.createTempFile("httpx-input", ".txt");
                try (java.io.FileWriter w = new java.io.FileWriter(tempFile)) {
                    w.write(target);
                }

                ProcessBuilder pb = new ProcessBuilder(binaryPath, "-list", tempFile.getAbsolutePath(), "-silent");
                pb.redirectErrorStream(true);
                Process process = pb.start();

                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
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
                process.waitFor();
                tempFile.delete();
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

### 4.2 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 4.3 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java
git commit -m "feat(scanner): 添加 HttpxScanner 适配器"
```

---

## Task 5: NucleiScanner 适配器

**Files:**
- Create: `backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java`

### 5.1 创建 NucleiScanner

```java
package com.secops.scanner.engine;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Component
public class NucleiScanner implements ScannerEngine {

    @Value("${scanner.nuclei.path:/usr/local/bin/nuclei}")
    private String binaryPath;

    @Override
    public String getName() {
        return "nuclei";
    }

    @Override
    public boolean isAvailable() {
        try {
            ProcessBuilder pb = new ProcessBuilder(binaryPath, "-version");
            Process p = pb.start();
            return p.waitFor() == 0;
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

            try {
                java.io.File tempFile = java.io.File.createTempFile("nuclei-input", ".txt");
                try (java.io.FileWriter w = new java.io.FileWriter(tempFile)) {
                    w.write(target);
                }

                java.io.File jsonFile = java.io.File.createTempFile("nuclei-output", ".json");

                ProcessBuilder pb = new ProcessBuilder(
                    binaryPath, "-list", tempFile.getAbsolutePath(),
                    "-jsonl", "-o", jsonFile.getAbsolutePath(),
                    "-rl", "150",
                    "-timeout", String.valueOf(options.getTimeout())
                );
                pb.redirectErrorStream(true);
                Process process = pb.start();
                process.waitFor();

                ObjectMapper mapper = new ObjectMapper();
                java.util.List<ScanResult.Finding> findings = new ArrayList<>();
                StringBuilder rawOutput = new StringBuilder();

                try (BufferedReader reader = new BufferedReader(new InputStreamReader(new java.io.FileInputStream(jsonFile)))) {
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
                            // skip malformed lines
                        }
                    }
                }

                tempFile.delete();
                jsonFile.delete();
                result.setFindings(findings);
                result.setRawOutput(java.util.Map.of("jsonLines", rawOutput.toString()));
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

### 5.2 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 5.3 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java
git commit -m "feat(scanner): 添加 NucleiScanner 适配器"
```

---

## Task 6: ScannerEngineService 扫描编排

**Files:**
- Create: `backend/src/main/java/com/secops/service/ScannerEngineService.java`
- Modify: `backend/src/main/java/com/secops/SecOpsApplication.java`

### 6.1 修改 SecOpsApplication 添加 @EnableAsync

在 `SecOpsApplication.java` 上添加 `@EnableAsync`：

```java
package com.secops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

@EnableAsync
@SpringBootApplication
public class SecOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecOpsApplication.class, args);
    }
}
```

### 6.2 创建 ScannerEngineService

```java
package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import com.secops.scanner.engine.*;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class ScannerEngineService {

    private final SubfinderScanner subfinderScanner;
    private final NaabuScanner naabuScanner;
    private final HttpxScanner httpxScanner;
    private final NucleiScanner nucleiScanner;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;

    public ScannerEngineService(SubfinderScanner subfinderScanner, NaabuScanner naabuScanner,
                                HttpxScanner httpxScanner, NucleiScanner nucleiScanner,
                                ScanTaskRepository scanTaskRepository, VulnerabilityRepository vulnerabilityRepository) {
        this.subfinderScanner = subfinderScanner;
        this.naabuScanner = naabuScanner;
        this.httpxScanner = httpxScanner;
        this.nucleiScanner = nucleiScanner;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    @Async
    public void runFullScan(ScanTask task) {
        try {
            task.setStatus(ScanStatus.RUNNING);
            task.setStartTime(LocalDateTime.now());
            task.setProgress(10);
            scanTaskRepository.save(task);

            String domain = task.getTarget().getDomain();

            // Step 1: Subfinder
            if (!subfinderScanner.isAvailable()) {
                throw new IllegalStateException("subfinder 未安装或不可用");
            }
            ScanResult subResult = subfinderScanner.scan(domain, new ScannerEngine.ScanOptions()).get();
            task.setProgress(30);
            task.setRawOutput("Subfinder: " + subResult.getFindings().size() + " subdomains\n");
            scanTaskRepository.save(task);

            String subdomains = subResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (subdomains.isEmpty()) subdomains = domain;

            // Step 2: Naabu
            if (!naabuScanner.isAvailable()) {
                throw new IllegalStateException("naabu 未安装或不可用");
            }
            ScanResult portResult = naabuScanner.scan(subdomains, new ScannerEngine.ScanOptions()).get();
            task.setProgress(50);
            task.setRawOutput(task.getRawOutput() + "Naabu: " + portResult.getFindings().size() + " ports\n");
            scanTaskRepository.save(task);

            String hostPorts = portResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (hostPorts.isEmpty()) hostPorts = domain;

            // Step 3: Httpx
            if (!httpxScanner.isAvailable()) {
                throw new IllegalStateException("httpx 未安装或不可用");
            }
            ScanResult aliveResult = httpxScanner.scan(hostPorts, new ScannerEngine.ScanOptions()).get();
            task.setProgress(70);
            task.setRawOutput(task.getRawOutput() + "Httpx: " + aliveResult.getFindings().size() + " alive hosts\n");
            scanTaskRepository.save(task);

            String aliveUrls = aliveResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (aliveUrls.isEmpty()) aliveUrls = domain;

            // Step 4: Nuclei
            if (!nucleiScanner.isAvailable()) {
                throw new IllegalStateException("nuclei 未安装或不可用");
            }
            ScanResult vulnResult = nucleiScanner.scan(aliveUrls, new ScannerEngine.ScanOptions()).get();
            task.setProgress(90);
            task.setRawOutput(task.getRawOutput() + "Nuclei: " + vulnResult.getFindings().size() + " findings\n");
            scanTaskRepository.save(task);

            // Save vulnerabilities
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

            task.setStatus(ScanStatus.COMPLETED);
            task.setProgress(100);
            task.setEndTime(LocalDateTime.now());
            scanTaskRepository.save(task);

        } catch (Exception e) {
            task.setStatus(ScanStatus.FAILED);
            task.setErrorMessage(e.getMessage());
            task.setEndTime(LocalDateTime.now());
            scanTaskRepository.save(task);
        }
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

### 6.3 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 6.4 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/SecOpsApplication.java backend/src/main/java/com/secops/service/ScannerEngineService.java
git commit -m "feat(scanner): 添加扫描编排 Service 和启用异步支持"
```

---

## Task 7: ScanTaskService + ScanTaskController

**Files:**
- Create: `backend/src/main/java/com/secops/service/ScanTaskService.java`
- Create: `backend/src/main/java/com/secops/controller/ScanTaskController.java`

### 7.1 创建 ScanTaskService

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

        scannerEngineService.runFullScan(task);
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

### 7.2 创建 ScanTaskController

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanType;
import com.secops.repository.VulnerabilityRepository;
import com.secops.service.ScanTaskService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scans")
public class ScanTaskController {

    private final ScanTaskService scanTaskService;
    private final VulnerabilityRepository vulnerabilityRepository;

    public ScanTaskController(ScanTaskService scanTaskService, VulnerabilityRepository vulnerabilityRepository) {
        this.scanTaskService = scanTaskService;
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    @GetMapping
    public R<List<ScanTask>> list() {
        return R.ok(scanTaskService.listAll());
    }

    @PostMapping
    public R<ScanTask> create(@RequestBody Map<String, Object> body) {
        Long targetId = Long.valueOf(body.get("targetId").toString());
        ScanType type = ScanType.valueOf(body.get("scanType").toString());
        return R.ok(scanTaskService.createScanTask(targetId, type));
    }

    @GetMapping("/{id}")
    public R<ScanTask> get(@PathVariable Long id) {
        return R.ok(scanTaskService.getTask(id));
    }

    @GetMapping("/{id}/vulns")
    public R<List<Vulnerability>> vulns(@PathVariable Long id) {
        return R.ok(vulnerabilityRepository.findByScanTaskId(id));
    }
}
```

### 7.3 验证编译

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 7.4 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/main/java/com/secops/service/ScanTaskService.java backend/src/main/java/com/secops/controller/ScanTaskController.java
git commit -m "feat(scanner): 添加扫描任务 Service 和 Controller"
```

---

## Task 8: 后端测试

**Files:**
- Create: `backend/src/test/java/com/secops/controller/ScanTaskControllerTest.java`

### 8.1 创建 ScanTaskControllerTest

```java
package com.secops.controller;

import com.secops.service.ScanTaskService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Collections;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(ScanTaskController.class)
@AutoConfigureMockMvc(addFilters = false)
class ScanTaskControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    ScanTaskService scanTaskService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void list_shouldReturnOk() throws Exception {
        when(scanTaskService.listAll()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/scans"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void create_shouldReturnOk() throws Exception {
        mockMvc.perform(post("/api/scans")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"targetId\":1,\"scanType\":\"FULL\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
```

### 8.2 运行测试

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn test -Dtest=ScanTaskControllerTest -q
```

**预期：** `BUILD SUCCESS`，测试通过

### 8.3 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add backend/src/test/java/com/secops/controller/ScanTaskControllerTest.java
git commit -m "test(scanner): 添加 ScanTaskController 集成测试"
```

---

## Task 9: 前端 ScanTasks 页面改造

**Files:**
- Modify: `frontend/src/pages/ScanTasks.jsx`

### 9.1 替换 ScanTasks.jsx

```jsx
import React, { useState, useEffect } from 'react'
import { Card, Button, Table, Tag, Progress, Space, Modal, Form, Select, message } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import client from '../api/client'

const statusMap = {
  'PENDING': { text: '等待中', color: 'default' },
  'RUNNING': { text: '运行中', color: 'blue' },
  'COMPLETED': { text: '完成', color: 'green' },
  'FAILED': { text: '失败', color: 'red' },
}

export default function ScanTasks() {
  const [data, setData] = useState([])
  const [targets, setTargets] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm()

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

  // Poll progress for running tasks every 3s
  useEffect(() => {
    const interval = setInterval(() => {
      if (data.some(d => d.status === 'RUNNING')) {
        fetchTasks()
      }
    }, 3000)
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

  const columns = [
    { title: '任务ID', dataIndex: 'taskId', key: 'taskId' },
    { title: '目标', dataIndex: ['target', 'domain'], key: 'target' },
    { title: '扫描类型', dataIndex: 'scanType', key: 'scanType' },
    { title: '状态', dataIndex: 'status', key: 'status',
      render: (s) => <Tag color={statusMap[s]?.color}>{statusMap[s]?.text || s}</Tag> },
    { title: '进度', dataIndex: 'progress', key: 'progress',
      render: (p) => <Progress percent={p} size="small" /> },
    { title: '操作', key: 'action',
      render: (_, record) => (
        <Space>
          <a>日志</a>
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
        <Table columns={columns} dataSource={data} loading={loading} />
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
              <Select.Option value="FULL">全量漏洞扫描</Select.Option>
            </Select>
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>开始扫描</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
```

### 9.2 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add frontend/src/pages/ScanTasks.jsx
git commit -m "feat(scanner): 扫描任务页面对接后端 API 并支持轮询进度"
```

---

## Task 10: 端到端联调验证

### 10.1 启动后端

```bash
export JAVA_HOME=/home/san/.local/jdk-17.0.12+7
export PATH=$JAVA_HOME/bin:$PATH
cd /home/san/IdeaProjects/secops-agent/backend && mvn spring-boot:run -q &
```

等待 ~20 秒，验证启动：

```bash
curl -s http://localhost:8080/api/scans
```

**预期：** `{"code":401,"msg":"未认证","data":null}`（说明后端运行正常，只是需要认证）

### 10.2 测试扫描任务 API

先登录获取 token：

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/api/auth/login -H "Content-Type: application/json" -d '{"username":"admin","password":"123456"}' | grep -o '"data":"[^"]*"' | cut -d'"' -f4)
```

测试创建扫描任务（需要先有一个目标）：

```bash
curl -s -X POST http://localhost:8080/api/scans \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $TOKEN" \
  -d '{"targetId":1,"scanType":"FULL"}'
```

**预期：** `{"code":200,"msg":"success","data":{"id":1,"taskId":"SCAN-...","status":"PENDING",...}}`

测试列表：

```bash
curl -s http://localhost:8080/api/scans -H "Authorization: Bearer $TOKEN"
```

**预期：** 返回包含刚才创建的任务

### 10.3 启动前端

```bash
cd /home/san/IdeaProjects/secops-agent/frontend && npm run dev &
```

访问 `http://localhost:5173/scans`：
- [ ] 已登录状态下显示扫描任务列表
- [ ] 点击"发起扫描"弹出 Modal
- [ ] Modal 中目标下拉框只显示已验证的目标
- [ ] 创建任务后列表自动刷新，显示新任务

### 10.4 Commit

```bash
cd /home/san/IdeaProjects/secops-agent && git add -A
git commit -m "feat(scanner): 完成扫描引擎集成 MVP，前后端联调通过"
```

---

## Self-Review

### Spec 覆盖检查

| Spec 要求 | 对应 Task |
|-----------|-----------|
| 枚举（ScanStatus, ScanType, Severity, VulnStatus） | Task 1 |
| ScanTask / Vulnerability 实体 | Task 1 |
| Repository | Task 1 |
| SubfinderScanner | Task 2 |
| NaabuScanner | Task 3 |
| HttpxScanner | Task 4 |
| NucleiScanner | Task 5 |
| ScannerEngineService 扫描编排 | Task 6 |
| @EnableAsync | Task 6 |
| ScanTaskService + Controller | Task 7 |
| 后端集成测试 | Task 8 |
| 前端 ScanTasks 对接 API + 轮询 | Task 9 |
| 全链路联调 | Task 10 |

无遗漏。

### Placeholder 扫描
- 无 "TBD"、"TODO"、"implement later"
- 所有代码块包含完整可运行代码
- 所有命令包含预期输出

### 类型一致性
- `ScanTask.id` 为 `Long`，Controller 路径参数和 Repository 泛型一致
- `ScanTask.status` 使用 `ScanStatus` 枚举，与 `ScannerEngineService` 中设置的状态一致
- `Vulnerability.severity` 使用 `Severity` 枚举，`parseSeverity()` 方法返回值匹配
- `ScanTaskController.create()` 接收 `targetId` (Long) 和 `scanType` (String)，转为 `ScanType` 枚举
- 前端 `statusMap` 键名与后端 `ScanStatus` 枚举名称一致
