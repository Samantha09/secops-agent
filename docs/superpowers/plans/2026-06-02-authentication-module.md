# Authentication 模块实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 为 SecOps Agent 平台引入 JWT 认证框架，包含用户注册/登录、前端路由守卫、Axios 自动携带 token，同时保持现有 Targets 模块功能不受影响。

**Architecture:** 标准 Spring Security + JWT 后端，React AuthContext + Axios interceptor 前端。MVP 阶段不引入 Team/多租户，所有业务数据全局可见。

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Security, JJWT 0.12, BCrypt, React 19, Axios, Ant Design

---

## 文件结构

### 后端新增
- `backend/src/main/java/com/secops/entity/User.java` — JPA 用户实体
- `backend/src/main/java/com/secops/repository/UserRepository.java` — 用户数据访问
- `backend/src/main/java/com/secops/security/JwtUtil.java` — JWT 生成与验证
- `backend/src/main/java/com/secops/security/JwtAuthenticationFilter.java` — 请求头 token 解析
- `backend/src/main/java/com/secops/service/AuthService.java` — 注册/登录业务
- `backend/src/main/java/com/secops/controller/AuthController.java` — 认证 REST API
- `backend/src/test/java/com/secops/controller/AuthControllerTest.java` — Controller 集成测试

### 后端修改
- `backend/src/main/java/com/secops/config/SecurityConfig.java` — 集成 JWT Filter，配置 401 JSON 响应

### 前端新增
- `frontend/src/context/AuthContext.jsx` — 全局认证状态管理
- `frontend/src/pages/Login.jsx` — 登录页面
- `frontend/src/pages/Register.jsx` — 注册页面

### 前端修改
- `frontend/src/api/client.js` — 请求拦截器附加 token，响应拦截器处理 401 跳转
- `frontend/src/App.jsx` — 增加登录/注册路由，增加 ProtectedRoute
- `frontend/src/main.jsx` — 用 AuthProvider 包裹 App
- `frontend/src/components/layout/AppLayout.jsx` — 头像下拉菜单增加退出登录

---

## Task 1: User 实体 + Repository

**Files:**
- Create: `backend/src/main/java/com/secops/entity/User.java`
- Create: `backend/src/main/java/com/secops/repository/UserRepository.java`

### 1.1 创建 User 实体

```java
package com.secops.entity;

import jakarta.persistence.*;
import lombok.Data;

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

    private String role;
}
```

### 1.2 创建 UserRepository

```java
package com.secops.repository;

import com.secops.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {
    Optional<User> findByUsername(String username);
    boolean existsByUsername(String username);
}
```

### 1.3 验证编译

```bash
cd backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 1.4 Commit

```bash
git add backend/src/main/java/com/secops/entity/User.java backend/src/main/java/com/secops/repository/UserRepository.java
git commit -m "feat(auth): 添加 User 实体和 Repository"
```

---

## Task 2: JwtUtil

**Files:**
- Create: `backend/src/main/java/com/secops/security/JwtUtil.java`

### 2.1 创建 JwtUtil

```java
package com.secops.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {

    // MVP 阶段硬编码，生产环境需外部配置
    private static final String SECRET = "secops-agent-jwt-secret-key-for-mvp-only-must-be-32-bytes-long!";
    private static final long EXPIRATION_MS = 86400000; // 24 小时

    private final SecretKey key = Keys.hmacShaKeyFor(SECRET.getBytes(StandardCharsets.UTF_8));

    public String generateToken(String username) {
        return Jwts.builder()
                .subject(username)
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + EXPIRATION_MS))
                .signWith(key)
                .compact();
    }

    public String extractUsername(String token) {
        return parseClaims(token).getSubject();
    }

    public boolean validateToken(String token) {
        try {
            parseClaims(token);
            return true;
        } catch (JwtException | IllegalArgumentException e) {
            return false;
        }
    }

    private Claims parseClaims(String token) {
        return Jwts.parser()
                .verifyWith(key)
                .build()
                .parseSignedClaims(token)
                .getPayload();
    }
}
```

### 2.2 验证编译

```bash
cd backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 2.3 Commit

```bash
git add backend/src/main/java/com/secops/security/JwtUtil.java
git commit -m "feat(auth): 添加 JWT 生成与验证工具类"
```

---

## Task 3: JwtAuthenticationFilter

**Files:**
- Create: `backend/src/main/java/com/secops/security/JwtAuthenticationFilter.java`

### 3.1 创建 JwtAuthenticationFilter

```java
package com.secops.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;

@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private final JwtUtil jwtUtil;

    public JwtAuthenticationFilter(JwtUtil jwtUtil) {
        this.jwtUtil = jwtUtil;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            String token = header.substring(7);
            if (jwtUtil.validateToken(token)) {
                String username = jwtUtil.extractUsername(token);
                UserDetails userDetails = new User(username, "", Collections.emptyList());
                UsernamePasswordAuthenticationToken auth =
                        new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
                SecurityContextHolder.getContext().setAuthentication(auth);
            }
        }
        chain.doFilter(request, response);
    }
}
```

### 3.2 验证编译

```bash
cd backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 3.3 Commit

```bash
git add backend/src/main/java/com/secops/security/JwtAuthenticationFilter.java
git commit -m "feat(auth): 添加 JWT 认证过滤器"
```

---

## Task 4: AuthService + AuthController

**Files:**
- Create: `backend/src/main/java/com/secops/service/AuthService.java`
- Create: `backend/src/main/java/com/secops/controller/AuthController.java`

### 4.1 创建 AuthService

```java
package com.secops.service;

import com.secops.common.R;
import com.secops.entity.User;
import com.secops.repository.UserRepository;
import com.secops.security.JwtUtil;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public R<String> register(String username, String password, String email) {
        if (userRepository.existsByUsername(username)) {
            return R.error(400, "用户名已存在");
        }
        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setRole("USER");
        userRepository.save(user);

        String token = jwtUtil.generateToken(username);
        return R.ok(token);
    }

    public R<String> login(String username, String password) {
        User user = userRepository.findByUsername(username).orElse(null);
        if (user == null || !passwordEncoder.matches(password, user.getPassword())) {
            return R.error(401, "用户名或密码错误");
        }
        String token = jwtUtil.generateToken(username);
        return R.ok(token);
    }
}
```

### 4.2 创建 AuthController

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.security.JwtUtil;
import com.secops.service.AuthService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;
    private final JwtUtil jwtUtil;

    @PostMapping("/register")
    public R<String> register(@RequestBody Map<String, String> body) {
        return authService.register(body.get("username"), body.get("password"), body.get("email"));
    }

    @PostMapping("/login")
    public R<String> login(@RequestBody Map<String, String> body) {
        return authService.login(body.get("username"), body.get("password"));
    }

    @GetMapping("/me")
    public R<Map<String, String>> me(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7);
            if (jwtUtil.validateToken(token)) {
                String username = jwtUtil.extractUsername(token);
                return R.ok(Map.of("username", username));
            }
        }
        return R.error(401, "未登录");
    }
}
```

### 4.3 验证编译

```bash
cd backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 4.4 Commit

```bash
git add backend/src/main/java/com/secops/service/AuthService.java backend/src/main/java/com/secops/controller/AuthController.java
git commit -m "feat(auth): 添加认证 Service 和 Controller"
```

---

## Task 5: SecurityConfig 集成 JWT Filter

**Files:**
- Modify: `backend/src/main/java/com/secops/config/SecurityConfig.java`

### 5.1 修改 SecurityConfig

完整替换文件内容：

```java
package com.secops.config;

import com.secops.security.JwtAuthenticationFilter;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthenticationFilter jwtAuthenticationFilter;

    public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter) {
        this.jwtAuthenticationFilter = jwtAuthenticationFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
            .csrf(AbstractHttpConfigurer::disable)
            .cors(cors -> cors.configurationSource(corsConfigurationSource()))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/api/auth/**").permitAll()
                .requestMatchers("/api/public/**").permitAll()
                .requestMatchers("/ws/**").permitAll()
                .anyRequest().authenticated()
            )
            .exceptionHandling(ex -> ex
                .authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                    response.setContentType("application/json;charset=UTF-8");
                    response.getWriter().write("{\"code\":401,\"msg\":\"未认证\",\"data\":null}");
                })
            )
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable)
            .addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOrigins(List.of("http://localhost:3000", "http://localhost:5173"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(true);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }
}
```

### 5.2 验证编译

```bash
cd backend && mvn compile -q
```

**预期：** `BUILD SUCCESS`

### 5.3 Commit

```bash
git add backend/src/main/java/com/secops/config/SecurityConfig.java
git commit -m "feat(auth): SecurityConfig 集成 JWT 过滤器并启用认证"
```

---

## Task 6: 后端测试

**Files:**
- Create: `backend/src/test/java/com/secops/controller/AuthControllerTest.java`

### 6.1 创建 AuthControllerTest

```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.security.JwtUtil;
import com.secops.service.AuthService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(AuthController.class)
@AutoConfigureMockMvc(addFilters = false)
class AuthControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockBean
    AuthService authService;

    @MockBean
    JwtUtil jwtUtil;

    @Autowired
    ObjectMapper objectMapper;

    @Test
    void register_shouldReturnOk() throws Exception {
        when(authService.register(any(), any(), any())).thenReturn(R.ok("token123"));

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test\",\"password\":\"123456\",\"email\":\"test@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").value("token123"));
    }

    @Test
    void login_shouldReturnOk() throws Exception {
        when(authService.login(any(), any())).thenReturn(R.ok("token123"));

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test\",\"password\":\"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200));
    }
}
```

### 6.2 运行测试

```bash
cd backend && mvn test -Dtest=AuthControllerTest -q
```

**预期：** `BUILD SUCCESS`，测试通过

### 6.3 Commit

```bash
git add backend/src/test/java/com/secops/controller/AuthControllerTest.java
git commit -m "test(auth): 添加 AuthController 集成测试"
```

---

## Task 7: 前端 AuthContext

**Files:**
- Create: `frontend/src/context/AuthContext.jsx`

### 7.1 创建 AuthContext

```jsx
import React, { createContext, useContext, useState, useEffect } from 'react'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [token, setToken] = useState(() => localStorage.getItem('token'))
  const [isReady, setIsReady] = useState(false)

  useEffect(() => {
    setIsReady(true)
  }, [])

  const login = (newToken) => {
    localStorage.setItem('token', newToken)
    setToken(newToken)
  }

  const logout = () => {
    localStorage.removeItem('token')
    setToken(null)
  }

  const isLoggedIn = !!token

  if (!isReady) {
    return null
  }

  return (
    <AuthContext.Provider value={{ token, isLoggedIn, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  const context = useContext(AuthContext)
  if (!context) {
    throw new Error('useAuth must be used within AuthProvider')
  }
  return context
}
```

### 7.2 Commit

```bash
git add frontend/src/context/AuthContext.jsx
git commit -m "feat(auth): 添加前端 AuthContext"
```

---

## Task 8: 前端 Axios Client 增加认证

**Files:**
- Modify: `frontend/src/api/client.js`

### 8.1 修改 client.js

完整替换文件内容：

```javascript
import axios from 'axios'

const client = axios.create({
  baseURL: '/api',
  timeout: 10000,
  headers: {
    'Content-Type': 'application/json',
  },
})

client.interceptors.request.use(
  (config) => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  (error) => Promise.reject(error)
)

client.interceptors.response.use(
  (response) => response.data,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      window.location.href = '/login'
      return Promise.reject(new Error('登录已过期，请重新登录'))
    }
    const msg = error.response?.data?.msg || '请求失败'
    return Promise.reject(new Error(msg))
  }
)

export default client
```

### 8.2 Commit

```bash
git add frontend/src/api/client.js
git commit -m "feat(auth): Axios 请求拦截器自动携带 token，401 自动跳转登录页"
```

---

## Task 9: 前端登录/注册页面

**Files:**
- Create: `frontend/src/pages/Login.jsx`
- Create: `frontend/src/pages/Register.jsx`

### 9.1 创建 Login.jsx

```jsx
import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message } from 'antd'
import { useAuth } from '../context/AuthContext'
import client from '../api/client'

export default function Login() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const { login } = useAuth()

  const onFinish = async (values) => {
    setLoading(true)
    try {
      const res = await client.post('/auth/login', values)
      if (res.code === 200) {
        login(res.data)
        message.success('登录成功')
        navigate('/dashboard')
      } else {
        message.error(res.msg)
      }
    } catch (e) {
      message.error(e.message || '登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card title="SecOps Agent 登录" style={{ width: 360 }}>
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, message: '请输入密码' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>登录</Button>
          </Form.Item>
          <Button type="link" onClick={() => navigate('/register')} block>没有账号？去注册</Button>
        </Form>
      </Card>
    </div>
  )
}
```

### 9.2 创建 Register.jsx

```jsx
import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message } from 'antd'
import { useAuth } from '../context/AuthContext'
import client from '../api/client'

export default function Register() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const { login } = useAuth()

  const onFinish = async (values) => {
    setLoading(true)
    try {
      const res = await client.post('/auth/register', values)
      if (res.code === 200) {
        login(res.data)
        message.success('注册成功')
        navigate('/dashboard')
      } else {
        message.error(res.msg)
      }
    } catch (e) {
      message.error(e.message || '注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card title="SecOps Agent 注册" style={{ width: 360 }}>
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: true, type: 'email', message: '请输入有效邮箱' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, min: 6, message: '密码至少6位' }]}>
            <Input.Password />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" loading={loading} block>注册</Button>
          </Form.Item>
          <Button type="link" onClick={() => navigate('/login')} block>已有账号？去登录</Button>
        </Form>
      </Card>
    </div>
  )
}
```

### 9.3 Commit

```bash
git add frontend/src/pages/Login.jsx frontend/src/pages/Register.jsx
git commit -m "feat(auth): 添加登录和注册页面"
```

---

## Task 10: 前端路由与布局集成

**Files:**
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/main.jsx`
- Modify: `frontend/src/components/layout/AppLayout.jsx`

### 10.1 修改 App.jsx

完整替换文件内容：

```jsx
import React from 'react'
import { Routes, Route, Navigate } from 'react-router-dom'
import { Layout } from 'antd'
import { useAuth } from './context/AuthContext'
import AppLayout from './components/layout/AppLayout'
import Login from './pages/Login'
import Register from './pages/Register'
import Dashboard from './pages/Dashboard'
import Targets from './pages/Targets'
import ScanTasks from './pages/ScanTasks'
import Vulnerabilities from './pages/Vulnerabilities'
import Tickets from './pages/Tickets'
import AgentChat from './pages/AgentChat'

const { Content } = Layout

function ProtectedRoute({ children }) {
  const { isLoggedIn } = useAuth()
  return isLoggedIn ? children : <Navigate to="/login" replace />
}

function App() {
  const { isLoggedIn } = useAuth()

  return (
    <Routes>
      <Route path="/login" element={isLoggedIn ? <Navigate to="/dashboard" /> : <Login />} />
      <Route path="/register" element={isLoggedIn ? <Navigate to="/dashboard" /> : <Register />} />
      <Route path="/" element={<Navigate to="/dashboard" replace />} />
      <Route path="*" element={
        <ProtectedRoute>
          <AppLayout>
            <Content style={{ margin: '24px 16px', padding: 24, background: '#fff', borderRadius: 8 }}>
              <Routes>
                <Route path="/dashboard" element={<Dashboard />} />
                <Route path="/targets" element={<Targets />} />
                <Route path="/scans" element={<ScanTasks />} />
                <Route path="/vulns" element={<Vulnerabilities />} />
                <Route path="/tickets" element={<Tickets />} />
                <Route path="/agent" element={<AgentChat />} />
              </Routes>
            </Content>
          </AppLayout>
        </ProtectedRoute>
      } />
    </Routes>
  )
}

export default App
```

### 10.2 修改 main.jsx

完整替换文件内容：

```jsx
import React from 'react'
import ReactDOM from 'react-dom/client'
import { BrowserRouter } from 'react-router-dom'
import { AuthProvider } from './context/AuthContext'
import App from './App'
import './index.css'

ReactDOM.createRoot(document.getElementById('root')).render(
  <React.StrictMode>
    <BrowserRouter>
      <AuthProvider>
        <App />
      </AuthProvider>
    </BrowserRouter>
  </React.StrictMode>,
)
```

### 10.3 修改 AppLayout.jsx

在文件中增加 `Dropdown`, `LogoutOutlined` 的导入、`useAuth` 的导入，以及用户下拉菜单。以下是修改点：

**导入部分** 替换为：

```jsx
import {
  Layout,
  Menu,
  Avatar,
  Badge,
  Space,
  Typography,
  Dropdown,
} from 'antd'
import {
  DashboardOutlined,
  GlobalOutlined,
  ScanOutlined,
  BugOutlined,
  FileTextOutlined,
  RobotOutlined,
  LogoutOutlined,
} from '@ant-design/icons'
import { useAuth } from '../../context/AuthContext'
```

**组件体内部**，在 `const location = useLocation()` 下一行添加：

```jsx
  const { logout } = useAuth()

  const userMenuItems = [
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }
  ]
```

**Avatar 部分** 替换为：

```jsx
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Avatar style={{ backgroundColor: '#1677ff', cursor: 'pointer' }}>S</Avatar>
            </Dropdown>
```

### 10.4 Commit

```bash
git add frontend/src/App.jsx frontend/src/main.jsx frontend/src/components/layout/AppLayout.jsx
git commit -m "feat(auth): 集成路由守卫、AuthProvider 和退出登录"
```

---

## Task 11: 端到端联调验证

### 11.1 启动后端

```bash
cd backend && mvn spring-boot:run
```

**验证 checklist：**
- [ ] 应用正常启动，无异常
- [ ] `/actuator/health` 返回 `UP`

### 11.2 测试认证 API

在另一个终端测试注册：

```bash
curl -X POST http://localhost:8080/api/auth/register \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456","email":"admin@example.com"}'
```

**预期：** `{"code":200,"msg":"success","data":"eyJhbGciOiJIUzI1NiIs..."}`

测试登录：

```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin","password":"123456"}'
```

**预期：** 同上，返回 token

测试未认证访问 Targets API：

```bash
curl http://localhost:8080/api/targets
```

**预期：** `{"code":401,"msg":"未认证","data":null}`

测试携带 token 访问 Targets API：

```bash
curl http://localhost:8080/api/targets \
  -H "Authorization: Bearer <上面获取的token>"
```

**预期：** `{"code":200,"msg":"success","data":[...]}`

### 11.3 启动前端并验证

```bash
cd frontend && npm run dev
```

访问 `http://localhost:5173/login`：
- [ ] 显示登录页面
- [ ] 输入用户名密码登录后跳转 Dashboard
- [ ] localStorage 中保存 `token`

访问 `http://localhost:5173/targets`：
- [ ] 已登录状态下正常加载目标列表
- [ ] Network 面板中请求携带 `Authorization: Bearer ...`

清除 localStorage 的 token 后刷新页面：
- [ ] 自动跳转 `/login`

点击 AppLayout 头像下拉菜单中的"退出登录"：
- [ ] localStorage token 被清除
- [ ] 页面跳转 `/login`

### 11.4 Commit

```bash
git add -A
git commit -m "feat(auth): 完成认证模块 MVP 实现，前后端联调通过"
```

---

## Self-Review

### Spec 覆盖检查

| Spec 要求 | 对应 Task |
|-----------|-----------|
| User 实体（username, password, email, role） | Task 1 |
| JWT 生成/解析/验证 | Task 2 |
| JWT Filter 集成 Spring Security | Task 3, Task 5 |
| 注册/登录 REST API | Task 4 |
| 401 统一 JSON 响应 | Task 5 |
| 前端 AuthContext | Task 7 |
| Axios 自动携带 token + 401 跳转 | Task 8 |
| 登录/注册页面 | Task 9 |
| 路由守卫 + ProtectedRoute | Task 10 |
| 后端集成测试 | Task 6 |
| 全链路联调 | Task 11 |

无遗漏。

### Placeholder 扫描
- 无 "TBD"、"TODO"、"implement later"
- 所有代码块包含完整可运行代码
- 所有命令包含预期输出

### 类型一致性
- `User.id` 为 `Long`，与 Repository 泛型一致
- `AuthService` 返回 `R<String>`，Controller 中保持一致
- `client.js` 的 401 处理与 SecurityConfig 的 401 响应格式匹配
- `App.jsx` 中 `ProtectedRoute` 包裹所有业务路由，与 `AuthContext.isLoggedIn` 状态一致
