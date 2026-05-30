# Targets 模块 MVP 设计

## 目标
让 SecOps Agent 项目完整跑通一个最小可用链路：添加扫描目标 → DNS TXT 验证 → 查询目标列表。

## 数据库实体

### Target
| 字段 | 类型 | 说明 |
|------|------|------|
| id | Long (PK) | 自增主键 |
| domain | String | 域名，唯一 |
| verified | boolean | 是否通过 DNS TXT 验证 |
| txtRecord | String | 系统生成的 TXT 记录值 |
| txtVerifiedAt | LocalDateTime | 验证通过时间 |
| subdomains | int | 子域名数量（暂由用户填写，后续自动扫描） |
| ports | int | 开放端口数量（暂由用户填写） |
| lastScanAt | LocalDateTime | 最后扫描时间 |
| createdAt | LocalDateTime | 创建时间 |
| updatedAt | LocalDateTime | 更新时间 |

## REST API

| 接口 | 方法 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `/api/targets` | GET | - | `R<List<TargetDTO>>` | 查询所有目标 |
| `/api/targets` | POST | `CreateTargetRequest` | `R<TargetDTO>` | 添加目标，自动生成 TXT 记录值 |
| `/api/targets/{id}/verify` | POST | - | `R<TargetDTO>` | 执行 DNS TXT 验证 |
| `/api/targets/{id}` | DELETE | - | `R<Void>` | 删除目标 |

## DTO

### CreateTargetRequest
- domain: String (必填，合法域名格式)

### TargetDTO
- 与 Target 实体字段一致，排除内部敏感字段

## 业务规则

1. **域名唯一性**：同一域名不能重复添加
2. **TXT 生成**：添加目标时自动生成随机字符串作为验证值，格式 `secops-verify=<uuid>`
3. **DNS 验证**：用户将 TXT 记录添加到域名 DNS 后，调用 verify 接口，后端通过 `InetAddress` / `javax.naming.dns.DnsContext` 查询 DNS TXT 记录，匹配即验证通过
4. **时序**：未验证的目标可以删除，已验证的目标也可以删除

## 前端改动

1. Targets 页面从 Mock 数据改为调用 `/api/targets`
2. "添加目标"按钮弹出表单（域名输入）
3. 添加成功后展示 TXT 验证指引
4. 列表显示验证状态，支持"验证"操作

## 依赖

- Spring Boot 3.4 + Spring Data JPA
- PostgreSQL（通过 docker-compose 启动）
- Redis（预留，本模块暂不直接使用）
- Axios（前端 HTTP 客户端）

## 非目标（本阶段不做）

- 子域名自动扫描（Subfinder 集成留到后续迭代）
- 端口扫描（Naabu 集成留到后续迭代）
- 用户登录鉴权（SecurityConfig 暂时放行所有请求）
- 分页查询（数据量小，先返回全部）
