# 扫描引擎集成模块设计文档

## 背景与目标

认证模块已完成，Targets 模块已支持域名管理和 DNS TXT 验证。本模块目标是为 SecOps Agent 引入真实的扫描引擎集成，实现：选择已验证目标 → 发起扫描任务 → 异步执行扫描流水线 → 保存漏洞结果。让前后端真实跑通扫描全链路。

## 关键决策

- **扫描执行方式**：选择"真实调用外部工具"（方案 A）。扫描器适配器通过 `ProcessBuilder` 调用 `subfinder`/`naabu`/`httpx`/`nuclei` 命令行工具，解析标准输出为结构化数据。
- **数据隔离策略**：延续认证模块的"暂不隔离"策略。`ScanTask` 和 `Vulnerability` 不绑定 `Team` 或 `User`，所有用户共享相同的扫描数据。
- **进度同步方式**：前端 3 秒轮询。MVP 阶段不引入 WebSocket，通过定时刷新 `GET /api/scans` 获取最新进度。

## 实体设计

### 枚举

```java
public enum ScanStatus { PENDING, RUNNING, COMPLETED, FAILED }
public enum ScanType { FULL, SUBDOMAIN, PORT, VULN }
public enum Severity { CRITICAL, HIGH, MEDIUM, LOW, INFO }
public enum VulnStatus { OPEN, FIXED, FALSE_POSITIVE }
```

### ScanTask（扫描任务）

```java
@Entity
@Data
public class ScanTask {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(unique = true)
    private String taskId; // SCAN-20250602-001
    
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

### Vulnerability（漏洞）

```java
@Entity
@Data
public class Vulnerability {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
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

- `ScanTask` 关联 `Target`（ManyToOne）
- `Vulnerability` 关联 `ScanTask`（ManyToOne）

## 扫描引擎适配器架构

四个适配器均实现已有的 `ScannerEngine` 接口：

| 适配器 | 调用命令 | 输入 | 输出解析 |
|--------|---------|------|----------|
| `SubfinderScanner` | `subfinder -d <domain> -all` | 域名 | 逐行读取 stdout，每行是一个子域名 |
| `NaabuScanner` | `naabu -list <file> -p -` | 子域名列表（写入临时文件） | 逐行读取 stdout，每行是 `host:port` |
| `HttpxScanner` | `httpx -list <file> -silent` | 主机列表（写入临时文件） | 逐行读取 stdout，每行是存活 URL |
| `NucleiScanner` | `nuclei -list <file> -jsonl -o <file>` | 存活 URL 列表（写入临时文件） | 解析输出的 JSONL 文件，每条转为 `Finding` |

每个适配器提供 `isAvailable()` 方法，通过执行 `<tool> -version` 判断工具是否已安装。

`NucleiScanner` 的特殊处理：
- 使用 `-jsonl` 参数输出 JSON Lines 到临时文件
- 用 Jackson `ObjectMapper` 逐行解析
- 提取 `template-id`、`info.name`、`info.severity`、`info.description`、`matched-at` 等字段
- 解析失败的行直接跳过，不中断扫描

## 扫描调度与业务层

### ScannerEngineService（扫描编排）

```java
@Service
public class ScannerEngineService {
    
    @Async
    public void runFullScan(ScanTask task) {
        // Step 1: Subfinder (progress 10% → 30%)
        // Step 2: Naabu (progress 30% → 50%)  
        // Step 3: Httpx (progress 50% → 70%)
        // Step 4: Nuclei (progress 70% → 90%)
        // Step 5: 保存漏洞 + 完成任务 (progress 100%)
    }
}
```

扫描流水线逻辑：
1. `SubfinderScanner` 发现子域名 → 结果拼接为换行字符串传入下一步
2. `NaabuScanner` 扫描开放端口 → 结果拼接传入下一步
3. `HttpxScanner` 探测存活 URL → 结果拼接传入下一步
4. `NucleiScanner` 执行漏洞扫描 → 解析 `Finding` 列表
5. 每个 `Finding` 转换为 `Vulnerability` 实体并保存
6. 每步更新 `task.progress` 和 `task.rawOutput`
7. 异常时 `task.status = FAILED`，记录 `errorMessage`

### ScanTaskService + ScanTaskController

API 设计：

| 端点 | 方法 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `GET /api/scans` | GET | — | `R<List<ScanTask>>` | 查询所有扫描任务 |
| `POST /api/scans` | POST | `{ targetId, scanType }` | `R<ScanTask>` | 创建任务并触发异步扫描 |
| `GET /api/scans/{id}` | GET | — | `R<ScanTask>` | 查询任务详情 |
| `GET /api/scans/{id}/vulns` | GET | — | `R<List<Vulnerability>>` | 查询任务发现的漏洞 |

### 异步支持

在 `SecOpsApplication.java` 上添加 `@EnableAsync` 注解启用 Spring 异步方法支持。

## 前端集成

### ScanTasks.jsx 改造要点

- `useEffect` 加载扫描任务列表
- `useEffect` 加载已验证目标列表（用于"发起扫描"Modal 下拉选择）
- `useEffect` 轮询：如果存在 `RUNNING` 状态任务，每 3 秒自动 `GET /api/scans` 刷新
- "发起扫描"Modal：选择目标（仅 `verified=true` 的 Target）和扫描类型
- 表格列：任务ID、目标域名、扫描类型、状态标签（颜色区分）、进度条、操作链接
- 状态颜色映射：`PENDING=默认`、`RUNNING=蓝色`、`COMPLETED=绿色`、`FAILED=红色`

## 文件清单

### 后端新增
- `entity/enums/ScanStatus.java`
- `entity/enums/ScanType.java`
- `entity/enums/Severity.java`
- `entity/enums/VulnStatus.java`
- `entity/ScanTask.java`
- `entity/Vulnerability.java`
- `repository/ScanTaskRepository.java`
- `repository/VulnerabilityRepository.java`
- `scanner/engine/SubfinderScanner.java`
- `scanner/engine/NaabuScanner.java`
- `scanner/engine/HttpxScanner.java`
- `scanner/engine/NucleiScanner.java`
- `service/ScannerEngineService.java`
- `service/ScanTaskService.java`
- `controller/ScanTaskController.java`
- `test/controller/ScanTaskControllerTest.java`

### 后端修改
- `SecOpsApplication.java` — 添加 `@EnableAsync`

### 前端修改
- `pages/ScanTasks.jsx` — 对接真实 API

## 测试验证点

- [ ] `POST /api/scans` 创建任务后，`scan_tasks` 表新增记录，状态为 `RUNNING`
- [ ] 扫描执行过程中，`task.progress` 从 0 逐步增加到 100
- [ ] 扫描完成后，状态变为 `COMPLETED`
- [ ] 如果安装了扫描工具，`vulnerabilities` 表新增记录（或执行真实扫描）
- [ ] 如果未安装扫描工具，任务状态变为 `FAILED`，`errorMessage` 记录原因
- [ ] 前端页面加载时显示任务列表
- [ ] 前端"发起扫描"Modal 只能选择已验证的目标
- [ ] 扫描进行中时，进度条自动更新（轮询生效）
