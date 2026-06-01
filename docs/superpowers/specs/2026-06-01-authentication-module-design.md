# Authentication 模块设计文档

## 背景与目标

Targets 模块 MVP 已完成，但系统目前无任何认证机制（SecurityConfig 为 `anyRequest().permitAll()`）。本模块目标是在不破坏现有 Targets 功能的前提下，引入 JWT 认证框架，为后续扫描、漏洞、工单等模块提供统一的登录/鉴权基础。

## 关键决策

- **数据隔离策略**：选择"暂不隔离"（方案 C）。MVP 阶段所有用户共享相同的业务数据，不引入 `Team` 实体和多租户逻辑。后续如需扩展，只需在相关实体上添加 `user_id` 或 `team_id` 字段即可。
- **实现路径**：选择"标准 Spring Security + JWT"（方案 A）。符合 Spring Security 最佳实践，代码量适中。

## 实体设计

### User 实体

```java
@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    private String email;

    private String role; // ADMIN / USER，MVP 阶段仅作标记，不做 RBAC 校验
}
```

- 不创建 `Team` 实体，减少迁移成本
- `role` 字段为后续权限扩展预留
- 现有 `Target` 实体**不做任何修改**，保持零侵入

### UserRepository

```java
public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

## 后端架构

### 组件清单

| 组件 | 职责 |
|------|------|
| `JwtUtil` | JWT 生成、解析、验证 |
| `JwtAuthenticationFilter` | 从请求头提取 token，验证后写入 SecurityContext |
| `AuthService` | 注册（创建用户 + 返回 token）、登录（校验密码 + 返回 token） |
| `AuthController` | `/api/auth/register`、`/api/auth/login`、`/api/auth/me` |
| `SecurityConfig` | 集成 JWT Filter，放行 `/api/auth/**`，其他请求需认证 |

### SecurityConfig 关键配置

```java
.authorizeHttpRequests(auth -> auth
    .requestMatchers("/api/auth/**").permitAll()
    .requestMatchers("/api/public/**").permitAll()
    .requestMatchers("/ws/**").permitAll()
    .anyRequest().authenticated()
)
.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class)
```

### API 设计

| 端点 | 方法 | 请求体 | 响应 | 说明 |
|------|------|--------|------|------|
| `/api/auth/register` | POST | `{ username, password, email }` | `R<String>` (token) | 注册并登录 |
| `/api/auth/login` | POST | `{ username, password }` | `R<String>` (token) | 登录 |
| `/api/auth/me` | GET | `Authorization: Bearer ...` | `R<Map<String,String>>` | 获取当前用户信息 |

## 前端架构

### 组件清单

| 组件 | 职责 |
|------|------|
| `AuthContext` | 全局认证状态（token、isLoggedIn、login、logout） |
| `api/client.js` | Axios 实例，请求拦截器自动附加 token，响应拦截器处理 401 |
| `Login.jsx` | 登录页面，Ant Design Form |
| `Register.jsx` | 注册页面，Ant Design Form |
| `App.jsx` | 路由配置，增加 `/login`、`/register`、ProtectedRoute |

### App.jsx 路由结构

```
/login          → 未登录显示登录页，已登录跳转 /dashboard
/register       → 未登录显示注册页，已登录跳转 /dashboard
/dashboard      → ProtectedRoute
/targets        → ProtectedRoute
/scans          → ProtectedRoute
/vulns          → ProtectedRoute
/tickets        → ProtectedRoute
/agent          → ProtectedRoute
```

### Axios Interceptor 行为

- **请求拦截**：从 `localStorage` 读取 `token`，写入 `Authorization: Bearer <token>`
- **响应拦截**：状态码 401 时，清除 `localStorage.token`，跳转 `/login`

**注意**：现有 `client.js` 的响应拦截器将 `response.data` 直接返回（`response => response.data`），API 返回的 `R<T>` 结构为 `{ code, msg, data }`，前端调用后得到的直接就是该结构。

## 与现有 Targets 模块的兼容性

| 改动点 | 影响 | 处理方式 |
|--------|------|----------|
| SecurityConfig 从 `permitAll` 改为 `authenticated` | Targets API 会 401 | 先实现前端 auth interceptor，再切换 SecurityConfig |
| `client.js` 增加请求拦截器 | Targets API 调用自动携带 token | 无影响，后端 JWT filter 验证通过即可 |
| 新增 `AuthContext` | 需要包裹 App | 修改 `main.jsx` |

## 实现顺序

1. **后端基础**：User 实体 + Repository + JwtUtil + JwtAuthenticationFilter
2. **后端业务**：AuthService + AuthController
3. **前端基础**：AuthContext + Axios interceptor
4. **前端页面**：Login + Register
5. **前端路由**：App.jsx 改造 + AppLayout 增加退出登录
6. **联调**：SecurityConfig 切换为 `authenticated()`，验证 Targets 页面在登录后正常访问

## 测试验证点

- [ ] 注册接口返回 token，`users` 表新增记录，密码为 BCrypt 加密
- [ ] 登录接口返回正确 token
- [ ] 未携带 token 访问 `/api/targets` 返回 401
- [ ] 携带有效 token 访问 `/api/targets` 正常返回
- [ ] 前端登录后 localStorage 保存 token，跳转 Dashboard
- [ ] 前端未登录时访问 `/targets` 自动跳转 `/login`
- [ ] Targets 页面在登录后能正常加载列表
