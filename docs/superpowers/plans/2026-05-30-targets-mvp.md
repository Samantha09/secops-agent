# Targets 模块 MVP 实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 实现 Targets 模块完整链路：添加域名目标 → DNS TXT 验证 → 查询目标列表，让前后端真实跑通。

**Architecture:** Spring Boot 3.4 + Spring Data JPA + PostgreSQL 后端，React + Axios + Ant Design 前端。通过 REST API 交互，DNS TXT 验证使用 Java `javax.naming.directory` 查询 DNS。

**Tech Stack:** Java 17, Spring Boot 3.4, PostgreSQL 16, React 19, Vite, Ant Design, Axios, Maven, Docker Compose

---

## 文件结构

### 新增后端文件
- `backend/src/main/java/com/secops/entity/Target.java` — JPA 实体
- `backend/src/main/java/com/secops/repository/TargetRepository.java` — 数据访问
- `backend/src/main/java/com/secops/dto/TargetDTO.java` — 响应 DTO
- `backend/src/main/java/com/secops/dto/CreateTargetRequest.java` — 创建请求 DTO
- `backend/src/main/java/com/secops/service/TargetService.java` — 业务逻辑
- `backend/src/main/java/com/secops/controller/TargetController.java` — REST API
- `backend/src/test/java/com/secops/service/TargetServiceTest.java` — 服务层单元测试
- `backend/src/test/java/com/secops/controller/TargetControllerTest.java` — 控制器集成测试

### 修改后端文件
- `backend/src/main/java/com/secops/scanner/engine/ScannerEngine.java` — 补充缺失的 Lombok import
- `backend/src/main/java/com/secops/agent/core/AgentRuntime.java` — 补充缺失的 Lombok + List import
- `backend/src/main/java/com/secops/config/SecurityConfig.java` — 临时放行所有请求（本阶段不做鉴权）

### 新增前端文件
- `frontend/src/api/client.js` — Axios 实例配置
- `frontend/src/api/targets.js` — Targets API 封装

### 修改前端文件
- `frontend/src/pages/Targets.jsx` — 从 Mock 改为真实 API 调用
- `frontend/vite.config.js` — 添加开发代理

---

## Task 1: 环境准备

### 1.1 安装 Maven

**目标：** 安装 Maven 3.9+ 用于后端编译。

```bash
sudo apt-get update && sudo apt-get install -y maven
```

验证：
```bash
mvn -version
```

预期输出包含 `Apache Maven 3.9.x`。

### 1.2 安装前端依赖

**目标：** 安装 frontend 的 npm 依赖。

```bash
cd frontend && npm install
```

验证：
```bash
ls -d node_modules
```

### 1.3 启动基础设施

**目标：** 启动 PostgreSQL 和 Redis。

```bash
docker compose up -d postgres redis
```

验证：
```bash
docker compose ps
```

预期看到 `secops-postgres` 和 `secops-redis` 状态为 `Up`。

---

## Task 2: 修复现有编译错误

### 2.1 修复 ScannerEngine.java

**文件：** `backend/src/main/java/com/secops/scanner/engine/ScannerEngine.java`

当前文件缺少 `import lombok.Data;`。在文件顶部添加：

```java
package com.secops.scanner.engine;

import lombok.Data;

import java.util.concurrent.CompletableFuture;
```

### 2.2 修复 AgentRuntime.java

**文件：** `backend/src/main/java/com/secops/agent/core/AgentRuntime.java`

当前文件缺少 import。在文件顶部添加：

```java
package com.secops.agent.core;

import lombok.Data;

import java.util.List;
```

### 2.3 验证编译

```bash
cd backend && mvn compile
```

预期：`BUILD SUCCESS`。

---

## Task 3: 后端数据层

### 3.1 创建 Target 实体

**文件：** `backend/src/main/java/com/secops/entity/Target.java`

```java
package com.secops.entity;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Data
@Entity
@Table(name = "targets")
public class Target {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String domain;

    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "txt_record")
    private String txtRecord;

    @Column(name = "txt_verified_at")
    private LocalDateTime txtVerifiedAt;

    private int subdomains;

    private int ports;

    @Column(name = "last_scan_at")
    private LocalDateTime lastScanAt;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
```

### 3.2 创建 TargetRepository

**文件：** `backend/src/main/java/com/secops/repository/TargetRepository.java`

```java
package com.secops.repository;

import com.secops.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface TargetRepository extends JpaRepository<Target, Long> {
    Optional<Target> findByDomain(String domain);
    boolean existsByDomain(String domain);
}
```

### 3.3 验证实体建表

启动后端，检查 PostgreSQL 是否自动创建 `targets` 表：

```bash
cd backend && mvn spring-boot:run
```

在另一个终端：
```bash
docker exec -it secops-postgres psql -U secops -d secops -c "\dt"
```

预期看到 `targets` 表。

---

## Task 4: 后端 DTO 与业务层

### 4.1 创建 TargetDTO

**文件：** `backend/src/main/java/com/secops/dto/TargetDTO.java`

```java
package com.secops.dto;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class TargetDTO {
    private Long id;
    private String domain;
    private boolean verified;
    private String txtRecord;
    private LocalDateTime txtVerifiedAt;
    private int subdomains;
    private int ports;
    private LocalDateTime lastScanAt;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
```

### 4.2 创建 CreateTargetRequest

**文件：** `backend/src/main/java/com/secops/dto/CreateTargetRequest.java`

```java
package com.secops.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class CreateTargetRequest {

    @NotBlank(message = "域名不能为空")
    @Pattern(
        regexp = "^(?!-)[A-Za-z0-9-]{1,63}(?<!-)\\.(?!-)[A-Za-z0-9-]{1,63}(?<!-)(\\.[A-Za-z0-9-]{1,63})*$",
        message = "域名格式不正确"
    )
    private String domain;
}
```

### 4.3 创建 TargetService

**文件：** `backend/src/main/java/com/secops/service/TargetService.java`

```java
package com.secops.service;

import com.secops.dto.CreateTargetRequest;
import com.secops.dto.TargetDTO;
import com.secops.entity.Target;
import com.secops.repository.TargetRepository;
import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.naming.directory.Attributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import java.time.LocalDateTime;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TargetService {

    private final TargetRepository targetRepository;

    @Transactional(readOnly = true)
    public List<TargetDTO> listAll() {
        return targetRepository.findAll().stream()
                .map(this::toDTO)
                .collect(Collectors.toList());
    }

    @Transactional
    public TargetDTO create(CreateTargetRequest request) {
        if (targetRepository.existsByDomain(request.getDomain())) {
            throw new IllegalArgumentException("该域名已存在");
        }

        Target target = new Target();
        target.setDomain(request.getDomain());
        target.setTxtRecord("secops-verify=" + UUID.randomUUID());

        Target saved = targetRepository.save(target);
        return toDTO(saved);
    }

    @Transactional
    public TargetDTO verify(Long id) {
        Target target = targetRepository.findById(id)
                .orElseThrow(() -> new EntityNotFoundException("目标不存在"));

        if (target.isVerified()) {
            return toDTO(target);
        }

        boolean verified = checkTxtRecord(target.getDomain(), target.getTxtRecord());
        if (verified) {
            target.setVerified(true);
            target.setTxtVerifiedAt(LocalDateTime.now());
            targetRepository.save(target);
        }

        return toDTO(target);
    }

    @Transactional
    public void delete(Long id) {
        if (!targetRepository.existsById(id)) {
            throw new EntityNotFoundException("目标不存在");
        }
        targetRepository.deleteById(id);
    }

    private boolean checkTxtRecord(String domain, String expectedTxt) {
        try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
            env.put("java.naming.provider.url", "dns:");

            DirContext ctx = new InitialDirContext(env);
            Attributes attrs = ctx.getAttributes(domain, new String[]{"TXT"});
            String txt = attrs.get("TXT") != null ? attrs.get("TXT").get().toString() : "";
            ctx.close();

            return txt.contains(expectedTxt);
        } catch (Exception e) {
            return false;
        }
    }

    private TargetDTO toDTO(Target target) {
        TargetDTO dto = new TargetDTO();
        dto.setId(target.getId());
        dto.setDomain(target.getDomain());
        dto.setVerified(target.isVerified());
        dto.setTxtRecord(target.getTxtRecord());
        dto.setTxtVerifiedAt(target.getTxtVerifiedAt());
        dto.setSubdomains(target.getSubdomains());
        dto.setPorts(target.getPorts());
        dto.setLastScanAt(target.getLastScanAt());
        dto.setCreatedAt(target.getCreatedAt());
        dto.setUpdatedAt(target.getUpdatedAt());
        return dto;
    }
}
```

---

## Task 5: 后端 REST API

### 5.1 创建 TargetController

**文件：** `backend/src/main/java/com/secops/controller/TargetController.java`

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.dto.CreateTargetRequest;
import com.secops.dto.TargetDTO;
import com.secops.service.TargetService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/targets")
@RequiredArgsConstructor
public class TargetController {

    private final TargetService targetService;

    @GetMapping
    public R<List<TargetDTO>> list() {
        return R.ok(targetService.listAll());
    }

    @PostMapping
    public R<TargetDTO> create(@Valid @RequestBody CreateTargetRequest request) {
        return R.ok(targetService.create(request));
    }

    @PostMapping("/{id}/verify")
    public R<TargetDTO> verify(@PathVariable Long id) {
        return R.ok(targetService.verify(id));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        targetService.delete(id);
        return R.ok();
    }
}
```

### 5.2 修改 SecurityConfig 临时放行

**文件：** `backend/src/main/java/com/secops/config/SecurityConfig.java`

将 `.anyRequest().authenticated()` 改为：

```java
.anyRequest().permitAll()
```

这是本阶段的临时措施，后续迭代会补充 JWT 鉴权。

### 5.3 验证 API

启动后端：
```bash
cd backend && mvn spring-boot:run
```

测试创建目标：
```bash
curl -X POST http://localhost:8080/api/targets \
  -H "Content-Type: application/json" \
  -d '{"domain":"example.com"}'
```

预期返回 `R<TargetDTO>`，包含 `txtRecord` 字段。

测试列表：
```bash
curl http://localhost:8080/api/targets
```

预期返回包含刚才创建的域名。

---

## Task 6: 前端 API 层

### 6.1 创建 Axios 客户端

**文件：** `frontend/src/api/client.js`

```javascript
import axios from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    const msg = error.response?.data?.msg || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default client
```

### 6.2 创建 Targets API

**文件：** `frontend/src/api/targets.js`

```javascript
import client from './client'

export function listTargets() {
  return client.get('/targets')
}

export function createTarget(data) {
  return client.post('/targets', data)
}

export function verifyTarget(id) {
  return client.post(`/targets/${id}/verify`)
}

export function deleteTarget(id) {
  return client.delete(`/targets/${id}`)
}
```

### 6.3 配置 Vite 代理

**文件：** `frontend/vite.config.js`

修改现有配置，添加 `server.proxy`：

```javascript
import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
})
```

---

## Task 7: 前端 Targets 页面重构

### 7.1 重写 Targets.jsx

**文件：** `frontend/src/pages/Targets.jsx`

用真实 API 替换 Mock 数据，添加：
- `useEffect` 加载目标列表
- 添加目标的 Modal 表单
- 验证操作按钮
- 删除操作按钮
- 加载状态
- 错误提示

保留现有 Ant Design Table 结构，数据改为从 API 获取。

### 7.2 验证前端

启动前端：
```bash
cd frontend && npm run dev
```

访问 `http://localhost:5173/targets`，验证：
- [ ] 页面加载时自动请求 `/api/targets`
- [ ] 点击"添加目标"弹出表单，输入域名后提交成功
- [ ] 列表刷新显示新添加的目标
- [ ] 显示 TXT 验证记录值
- [ ] 验证按钮可用（DNS 验证逻辑可后续在真实域名上测试）
- [ ] 删除按钮可用

---

## Task 8: 后端测试

### 8.1 编写 TargetService 单元测试

**文件：** `backend/src/test/java/com/secops/service/TargetServiceTest.java`

```java
package com.secops.service;

import com.secops.dto.CreateTargetRequest;
import com.secops.dto.TargetDTO;
import com.secops.entity.Target;
import com.secops.repository.TargetRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TargetServiceTest {

    @Mock
    TargetRepository targetRepository;

    @InjectMocks
    TargetService targetService;

    @Test
    void listAll_shouldReturnAllTargets() {
        Target t = new Target();
        t.setId(1L);
        t.setDomain("example.com");
        when(targetRepository.findAll()).thenReturn(List.of(t));

        List<TargetDTO> result = targetService.listAll();

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getDomain()).isEqualTo("example.com");
    }

    @Test
    void create_shouldThrow_whenDomainExists() {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("example.com");
        when(targetRepository.existsByDomain("example.com")).thenReturn(true);

        assertThatThrownBy(() -> targetService.create(req))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("已存在");
    }

    @Test
    void create_shouldSaveTarget() {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("example.com");
        when(targetRepository.existsByDomain("example.com")).thenReturn(false);
        when(targetRepository.save(any(Target.class))).thenAnswer(i -> i.getArgument(0));

        TargetDTO result = targetService.create(req);

        assertThat(result.getDomain()).isEqualTo("example.com");
        assertThat(result.getTxtRecord()).startsWith("secops-verify=");
    }

    @Test
    void delete_shouldThrow_whenNotFound() {
        when(targetRepository.existsById(1L)).thenReturn(false);

        assertThatThrownBy(() -> targetService.delete(1L))
                .isInstanceOf(jakarta.persistence.EntityNotFoundException.class);
    }
}
```

### 8.2 编写 TargetController 集成测试

**文件：** `backend/src/test/java/com/secops/controller/TargetControllerTest.java`

```java
package com.secops.controller;

import com.secops.dto.CreateTargetRequest;
import com.secops.service.TargetService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(TargetController.class)
class TargetControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    TargetService targetService;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void list_shouldReturnOk() throws Exception {
        mockMvc.perform(get("/api/targets"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }

    @Test
    void create_shouldReturnOk() throws Exception {
        CreateTargetRequest req = new CreateTargetRequest();
        req.setDomain("example.com");

        mockMvc.perform(post("/api/targets")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
```

### 8.3 运行测试

```bash
cd backend && mvn test
```

预期：`BUILD SUCCESS`，所有测试通过。

---

## Task 9: 端到端验证

### 9.1 全链路测试

1. 启动后端：`cd backend && mvn spring-boot:run`
2. 启动前端：`cd frontend && npm run dev`
3. 访问 `http://localhost:5173/targets`

验证 checklist：
- [ ] 页面加载时请求 `/api/targets` 成功（Network 面板 200）
- [ ] 添加目标表单提交后，后端 `targets` 表新增记录
- [ ] 列表自动刷新，显示新目标
- [ ] TXT 记录值格式正确（`secops-verify=...`）
- [ ] 删除后列表刷新，数据库记录删除

### 9.2 Commit

```bash
git add -A
git commit -m "feat(target): 实现 Targets 模块 MVP"
git push origin main
```

---

## Self-Review

### Spec 覆盖检查
| Spec 要求 | 对应任务 |
|-----------|----------|
| Target 实体（domain, verified, txtRecord 等） | Task 3.1 |
| REST API（GET/POST/verify/DELETE） | Task 5.1 |
| DNS TXT 验证逻辑 | Task 4.3（Service.verify + checkTxtRecord） |
| 前端 API 对接 | Task 6 + Task 7 |
| 自测流程 | Task 9 |

无遗漏。

### Placeholder 扫描
- 无 "TBD"、"TODO"、"implement later"
- 所有代码块包含完整可运行代码
- 所有命令包含预期输出

### 类型一致性
- `Target.id` 为 `Long`，Controller/Service 中路径参数和返回值均为 `Long`
- `CreateTargetRequest.domain` 使用 `@Pattern` 校验，与 Spec 中"合法域名格式"一致
- `TargetDTO` 字段与 `Target` 实体一一对应

---

## 执行方式选择

计划已保存到 `docs/superpowers/plans/2026-05-30-targets-mvp.md`。

**两种执行方式：**

1. **Subagent-Driven（推荐）** — 每个 Task 派一个独立子代理执行，我负责审阅和集成，适合并行推进
2. **Inline Execution** — 在当前会话中按 Task 逐步执行，适合需要我持续介入和决策的场景

**哪种方式？**
