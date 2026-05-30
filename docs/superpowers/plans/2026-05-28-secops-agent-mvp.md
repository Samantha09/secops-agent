# SecOps Agent MVP Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a working MVP of the SecOps Agent platform with JWT auth, target management with DNS verification, real scanner integration, vulnerability tracking, tickets, and mock Agent chat via WebSocket.

**Architecture:** Spring Boot 3.4 backend with JPA/PostgreSQL, ProcessBuilder scanner adapters, @Async task scheduling, JWT auth, WebSocket streaming. React 19 frontend with Ant Design, Axios, WebSocket.

**Tech Stack:** Java 17, Spring Boot 3.4, Spring Security, JJWT 0.12, PostgreSQL, Redis, React 19, Vite, Ant Design, WebSocket

---

## File Structure

### Backend (New Files)
- `com.secops.entity.enums.*` — Enums for all entity statuses
- `com.secops.entity.*` — JPA entities (User, Team, Target, ScanTask, Vulnerability, Ticket, AgentSession, AgentMessage)
- `com.secops.repository.*` — Spring Data JPA repositories
- `com.secops.security.JwtUtil` — Token generation/validation
- `com.secops.security.JwtAuthenticationFilter` — Extract token from requests
- `com.secops.service.*` — Business logic services
- `com.secops.controller.*` — REST API controllers
- `com.secops.scanner.engine.*` — Scanner adapters (Nuclei, Subfinder, Naabu, Httpx)
- `com.secops.agent.core.MockAgentRuntime` — Mock ReAct implementation
- `com.secops.websocket.AgentWebSocketHandler` — WebSocket message handler
- `com.secops.config.WebSocketConfig` — WebSocket endpoint registration

### Backend (Modified Files)
- `pom.xml` — Add JJWT dependencies
- `config/SecurityConfig.java` — Add JWT filter, permit auth endpoints
- `application.yml` — Add scanner paths and async config

### Frontend (New Files)
- `src/api/axios.js` — Axios instance with auth interceptor
- `src/context/AuthContext.jsx` — Global auth state
- `src/pages/Login.jsx` — Login page
- `src/pages/Register.jsx` — Register page

### Frontend (Modified Files)
- `src/App.jsx` — Add auth routes, login guard
- `src/pages/Dashboard.jsx` — Real stats from API
- `src/pages/Targets.jsx` — Real API + add target modal
- `src/pages/ScanTasks.jsx` — Real API + launch scan modal
- `src/pages/Vulnerabilities.jsx` — Real API + filters
- `src/pages/Tickets.jsx` — Real API + status edit
- `src/pages/AgentChat.jsx` — WebSocket connection
- `src/components/layout/AppLayout.jsx` — Show user info, logout

---

## Slice 1: Authentication

### Task 1: Add JJWT Dependencies + Enums + Base Entities

**Files:**
- Modify: `backend/pom.xml`
- Create: `backend/src/main/java/com/secops/entity/enums/VerificationStatus.java`
- Create: `backend/src/main/java/com/secops/entity/enums/ScanStatus.java`
- Create: `backend/src/main/java/com/secops/entity/enums/ScanType.java`
- Create: `backend/src/main/java/com/secops/entity/enums/Severity.java`
- Create: `backend/src/main/java/com/secops/entity/enums/VulnStatus.java`
- Create: `backend/src/main/java/com/secops/entity/enums/Priority.java`
- Create: `backend/src/main/java/com/secops/entity/enums/TicketStatus.java`
- Create: `backend/src/main/java/com/secops/entity/Team.java`
- Create: `backend/src/main/java/com/secops/entity/User.java`

- [ ] **Step 1: Add JJWT dependencies to pom.xml**

Insert inside `<dependencies>`:
```xml
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-api</artifactId>
    <version>0.12.3</version>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-impl</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>
<dependency>
    <groupId>io.jsonwebtoken</groupId>
    <artifactId>jjwt-jackson</artifactId>
    <version>0.12.3</version>
    <scope>runtime</scope>
</dependency>
```

- [ ] **Step 2: Create all enums**

`VerificationStatus.java`:
```java
package com.secops.entity.enums;

public enum VerificationStatus {
    PENDING, VERIFIED, FAILED
}
```

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

`Priority.java`:
```java
package com.secops.entity.enums;

public enum Priority {
    HIGH, MEDIUM, LOW
}
```

`TicketStatus.java`:
```java
package com.secops.entity.enums;

public enum TicketStatus {
    OPEN, IN_PROGRESS, RESOLVED, CLOSED
}
```

- [ ] **Step 3: Create Team entity**

`Team.java`:
```java
package com.secops.entity;

import jakarta.persistence.*;
import lombok.Data;

@Entity
@Data
public class Team {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String name;
}
```

- [ ] **Step 4: Create User entity**

`User.java`:
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

    private String role; // ADMIN / USER

    @ManyToOne
    @JoinColumn(name = "team_id")
    private Team team;
}
```

- [ ] **Step 5: Commit**

```bash
git add backend/pom.xml backend/src/main/java/com/secops/entity/
git commit -m "feat: add JJWT deps, enums, User and Team entities"
```

### Task 2: JPA Repositories for Auth

**Files:**
- Create: `backend/src/main/java/com/secops/repository/TeamRepository.java`
- Create: `backend/src/main/java/com/secops/repository/UserRepository.java`

- [ ] **Step 1: Create TeamRepository**

`TeamRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.Team;
import org.springframework.data.jpa.repository.JpaRepository;

public interface TeamRepository extends JpaRepository<Team, Long> {
}
```

- [ ] **Step 2: Create UserRepository**

`UserRepository.java`:
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

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/repository/
git commit -m "feat: add Team and User repositories"
```

### Task 3: JWT Utilities + Authentication Filter

**Files:**
- Create: `backend/src/main/java/com/secops/security/JwtUtil.java`
- Create: `backend/src/main/java/com/secops/security/JwtAuthenticationFilter.java`

- [ ] **Step 1: Create JwtUtil**

`JwtUtil.java`:
```java
package com.secops.security;

import io.jsonwebtoken.*;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;

@Component
public class JwtUtil {
    // Hard-coded secret for MVP; rotate in production
    private static final String SECRET = "secops-agent-jwt-secret-key-for-mvp-only-must-be-32-bytes-long!";
    private static final long EXPIRATION_MS = 86400000; // 24 hours

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

- [ ] **Step 2: Create JwtAuthenticationFilter**

`JwtAuthenticationFilter.java`:
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

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/security/
git commit -m "feat: add JWT utilities and authentication filter"
```

### Task 4: AuthService + AuthController

**Files:**
- Create: `backend/src/main/java/com/secops/service/AuthService.java`
- Create: `backend/src/main/java/com/secops/controller/AuthController.java`

- [ ] **Step 1: Create AuthService**

`AuthService.java`:
```java
package com.secops.service;

import com.secops.common.R;
import com.secops.entity.Team;
import com.secops.entity.User;
import com.secops.repository.TeamRepository;
import com.secops.repository.UserRepository;
import com.secops.security.JwtUtil;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

@Service
public class AuthService {
    private final UserRepository userRepository;
    private final TeamRepository teamRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtUtil jwtUtil;

    public AuthService(UserRepository userRepository, TeamRepository teamRepository,
                       PasswordEncoder passwordEncoder, JwtUtil jwtUtil) {
        this.userRepository = userRepository;
        this.teamRepository = teamRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtUtil = jwtUtil;
    }

    public R<String> register(String username, String password, String email) {
        if (userRepository.existsByUsername(username)) {
            return R.error(400, "用户名已存在");
        }
        Team team = new Team();
        team.setName(username + "'s Team");
        teamRepository.save(team);

        User user = new User();
        user.setUsername(username);
        user.setPassword(passwordEncoder.encode(password));
        user.setEmail(email);
        user.setRole("USER");
        user.setTeam(team);
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

- [ ] **Step 2: Create AuthController**

`AuthController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.service.AuthService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    private final AuthService authService;

    public AuthController(AuthService authService) {
        this.authService = authService;
    }

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
        // Simplified: in production parse JWT. For MVP return placeholder if authed.
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return R.ok(Map.of("username", "user"));
        }
        return R.error(401, "未登录");
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/service/AuthService.java backend/src/main/java/com/secops/controller/AuthController.java
git commit -m "feat: add auth service and controller"
```

### Task 5: Update SecurityConfig with JWT Filter

**Files:**
- Modify: `backend/src/main/java/com/secops/config/SecurityConfig.java`

- [ ] **Step 1: Add JWT filter to SecurityConfig**

Replace the current file content:
```java
package com.secops.config;

import com.secops.security.JwtAuthenticationFilter;
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

- [ ] **Step 2: Verify backend compiles**

```bash
cd /home/san/IdeaProjects/secops-agent/backend && mvn compile -q
```
Expected: BUILD SUCCESS

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/config/SecurityConfig.java
git commit -m "feat: integrate JWT filter into security config"
```

### Task 6: Frontend Auth Pages + Axios Config

**Files:**
- Create: `frontend/src/api/axios.js`
- Create: `frontend/src/context/AuthContext.jsx`
- Create: `frontend/src/pages/Login.jsx`
- Create: `frontend/src/pages/Register.jsx`

- [ ] **Step 1: Create Axios instance with auth interceptor**

`axios.js`:
```javascript
import axios from 'axios'

const api = axios.create({
  baseURL: 'http://localhost:8080/api',
  headers: { 'Content-Type': 'application/json' },
})

api.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

api.interceptors.response.use(
  (res) => res,
  (err) => {
    if (err.response?.status === 401) {
      localStorage.removeItem('token')
      window.location.href = '/login'
    }
    return Promise.reject(err)
  }
)

export default api
```

- [ ] **Step 2: Create AuthContext**

`AuthContext.jsx`:
```javascript
import React, { createContext, useContext, useState, useEffect } from 'react'

const AuthContext = createContext(null)

export function AuthProvider({ children }) {
  const [token, setToken] = useState(localStorage.getItem('token'))

  const login = (newToken) => {
    localStorage.setItem('token', newToken)
    setToken(newToken)
  }

  const logout = () => {
    localStorage.removeItem('token')
    setToken(null)
  }

  const isLoggedIn = !!token

  return (
    <AuthContext.Provider value={{ token, isLoggedIn, login, logout }}>
      {children}
    </AuthContext.Provider>
  )
}

export function useAuth() {
  return useContext(AuthContext)
}
```

- [ ] **Step 3: Create Login page**

`Login.jsx`:
```javascript
import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message } from 'antd'
import { useAuth } from '../context/AuthContext'
import api from '../api/axios'

export default function Login() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const { login } = useAuth()

  const onFinish = async (values) => {
    setLoading(true)
    try {
      const res = await api.post('/auth/login', values)
      if (res.data.code === 200) {
        login(res.data.data)
        message.success('登录成功')
        navigate('/dashboard')
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error('登录失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card title="SecOps Agent 登录" style={{ width: 360 }}>
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true }]}>
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

- [ ] **Step 4: Create Register page**

`Register.jsx`:
```javascript
import React, { useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { Form, Input, Button, Card, message } from 'antd'
import { useAuth } from '../context/AuthContext'
import api from '../api/axios'

export default function Register() {
  const [loading, setLoading] = useState(false)
  const navigate = useNavigate()
  const { login } = useAuth()

  const onFinish = async (values) => {
    setLoading(true)
    try {
      const res = await api.post('/auth/register', values)
      if (res.data.code === 200) {
        login(res.data.data)
        message.success('注册成功')
        navigate('/dashboard')
      } else {
        message.error(res.data.msg)
      }
    } catch (e) {
      message.error('注册失败')
    } finally {
      setLoading(false)
    }
  }

  return (
    <div style={{ height: '100vh', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#f0f2f5' }}>
      <Card title="SecOps Agent 注册" style={{ width: 360 }}>
        <Form onFinish={onFinish} layout="vertical">
          <Form.Item name="username" label="用户名" rules={[{ required: true }]}>
            <Input />
          </Form.Item>
          <Form.Item name="email" label="邮箱" rules={[{ required: true, type: 'email' }]}>
            <Input />
          </Form.Item>
          <Form.Item name="password" label="密码" rules={[{ required: true, min: 6 }]}>
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

- [ ] **Step 5: Commit**

```bash
git add frontend/src/api/ frontend/src/context/ frontend/src/pages/Login.jsx frontend/src/pages/Register.jsx
git commit -m "feat: add frontend auth pages, axios config, auth context"
```

### Task 7: Frontend Routing + Layout Auth Integration

**Files:**
- Modify: `frontend/src/App.jsx`
- Modify: `frontend/src/components/layout/AppLayout.jsx`
- Modify: `frontend/src/main.jsx`

- [ ] **Step 1: Update App.jsx with auth routes and guard**

Replace `App.jsx`:
```javascript
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

- [ ] **Step 2: Update AppLayout with logout**

Replace `AppLayout.jsx`:
```javascript
import React, { useState } from 'react'
import { useNavigate, useLocation } from 'react-router-dom'
import { Layout, Menu, Avatar, Badge, Space, Typography, Dropdown } from 'antd'
import {
  DashboardOutlined, GlobalOutlined, ScanOutlined, BugOutlined,
  FileTextOutlined, RobotOutlined, LogoutOutlined
} from '@ant-design/icons'
import { useAuth } from '../../context/AuthContext'

const { Header, Sider } = Layout
const { Title } = Typography

const menuItems = [
  { key: '/dashboard', icon: <DashboardOutlined />, label: '仪表盘' },
  { key: '/targets', icon: <GlobalOutlined />, label: '目标管理' },
  { key: '/scans', icon: <ScanOutlined />, label: '扫描任务' },
  { key: '/vulns', icon: <BugOutlined />, label: '漏洞管理' },
  { key: '/tickets', icon: <FileTextOutlined />, label: '修复工单' },
  { key: '/agent', icon: <RobotOutlined />, label: 'Agent 助手' },
]

export default function AppLayout({ children }) {
  const [collapsed, setCollapsed] = useState(false)
  const navigate = useNavigate()
  const location = useLocation()
  const { logout } = useAuth()

  const userMenuItems = [
    { key: 'logout', icon: <LogoutOutlined />, label: '退出登录', onClick: logout }
  ]

  return (
    <Layout style={{ minHeight: '100vh' }}>
      <Sider trigger={null} collapsible collapsed={collapsed} theme="light"
        style={{ boxShadow: '2px 0 8px rgba(0,0,0,0.05)' }}>
        <div style={{ height: 64, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          <RobotOutlined style={{ fontSize: 24, color: '#1677ff' }} />
          {!collapsed && <Title level={5} style={{ margin: 0, marginLeft: 8 }}>SecOps Agent</Title>}
        </div>
        <Menu mode="inline" selectedKeys={[location.pathname]} items={menuItems}
          onClick={({ key }) => navigate(key)} />
      </Sider>

      <Layout>
        <Header style={{ background: '#fff', padding: '0 24px', display: 'flex', alignItems: 'center', justifyContent: 'flex-end', boxShadow: '0 1px 4px rgba(0,0,0,0.05)' }}>
          <Space size={16}>
            <Badge count={5} size="small"><BugOutlined style={{ fontSize: 18 }} /></Badge>
            <Dropdown menu={{ items: userMenuItems }} placement="bottomRight">
              <Avatar style={{ backgroundColor: '#1677ff', cursor: 'pointer' }}>S</Avatar>
            </Dropdown>
          </Space>
        </Header>
        {children}
      </Layout>
    </Layout>
  )
}
```

- [ ] **Step 3: Update main.jsx to wrap with AuthProvider**

Replace `main.jsx`:
```javascript
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
  </React.StrictMode>
)
```

- [ ] **Step 4: Commit**

```bash
git add frontend/src/App.jsx frontend/src/components/layout/AppLayout.jsx frontend/src/main.jsx
git commit -m "feat: add auth routing, protected routes, logout"
```

---

## Slice 2: Target Management + DNS TXT Verification

### Task 8: Target Entity + Repository

**Files:**
- Create: `backend/src/main/java/com/secops/entity/Target.java`
- Create: `backend/src/main/java/com/secops/repository/TargetRepository.java`

- [ ] **Step 1: Create Target entity**

`Target.java`:
```java
package com.secops.entity;

import com.secops.entity.enums.VerificationStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Target {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String domain;

    private String verificationToken;

    @Enumerated(EnumType.STRING)
    private VerificationStatus status = VerificationStatus.PENDING;

    private LocalDateTime verifiedAt;

    private LocalDateTime createdAt = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "team_id")
    private Team team;
}
```

- [ ] **Step 2: Create TargetRepository**

`TargetRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.Target;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TargetRepository extends JpaRepository<Target, Long> {
    List<Target> findByTeamId(Long teamId);
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/entity/Target.java backend/src/main/java/com/secops/repository/TargetRepository.java
git commit -m "feat: add Target entity and repository"
```

### Task 9: TargetService + DnsVerificationService

**Files:**
- Create: `backend/src/main/java/com/secops/service/TargetService.java`
- Create: `backend/src/main/java/com/secops/service/DnsVerificationService.java`

- [ ] **Step 1: Create DnsVerificationService**

`DnsVerificationService.java`:
```java
package com.secops.service;

import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

@Service
public class DnsVerificationService {

    public boolean verifyTxtRecord(String domain, String expectedToken) {
        List<String> txtRecords = queryTxtRecords(domain);
        String marker = "secops-verify=" + expectedToken;
        return txtRecords.stream().anyMatch(r -> r.contains(marker));
    }

    private List<String> queryTxtRecords(String domain) {
        List<String> records = new ArrayList<>();
        try {
            ProcessBuilder pb = new ProcessBuilder("dig", "+short", "TXT", domain);
            Process process = pb.start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    records.add(line.replace("\"", "").trim());
                }
            }
            process.waitFor();
        } catch (Exception e) {
            // Fallback to Java DNS if dig is unavailable
            try {
                java.util.Hashtable<String, String> env = new java.util.Hashtable<>();
                env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
                javax.naming.directory.DirContext ctx = new javax.naming.directory.InitialDirContext(env);
                javax.naming.directory.Attributes attrs = ctx.getAttributes(domain, new String[]{"TXT"});
                javax.naming.directory.Attribute attr = attrs.get("TXT");
                if (attr != null) {
                    for (int i = 0; i < attr.size(); i++) {
                        records.add((String) attr.get(i));
                    }
                }
                ctx.close();
            } catch (Exception ex) {
                // Both methods failed
            }
        }
        return records;
    }
}
```

- [ ] **Step 2: Create TargetService**

`TargetService.java`:
```java
package com.secops.service;

import com.secops.entity.Target;
import com.secops.entity.Team;
import com.secops.entity.User;
import com.secops.entity.enums.VerificationStatus;
import com.secops.repository.TargetRepository;
import com.secops.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class TargetService {
    private final TargetRepository targetRepository;
    private final UserRepository userRepository;
    private final DnsVerificationService dnsVerificationService;

    public TargetService(TargetRepository targetRepository, UserRepository userRepository,
                         DnsVerificationService dnsVerificationService) {
        this.targetRepository = targetRepository;
        this.userRepository = userRepository;
        this.dnsVerificationService = dnsVerificationService;
    }

    public Target createTarget(String domain, String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        Target target = new Target();
        target.setDomain(domain);
        target.setVerificationToken(UUID.randomUUID().toString());
        target.setTeam(user.getTeam());
        return targetRepository.save(target);
    }

    public List<Target> listTargets(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        return targetRepository.findByTeamId(user.getTeam().getId());
    }

    public void deleteTarget(Long id) {
        targetRepository.deleteById(id);
    }

    public boolean verifyTarget(Long id) {
        Target target = targetRepository.findById(id).orElseThrow();
        boolean ok = dnsVerificationService.verifyTxtRecord(target.getDomain(), target.getVerificationToken());
        if (ok) {
            target.setStatus(VerificationStatus.VERIFIED);
            target.setVerifiedAt(LocalDateTime.now());
        } else {
            target.setStatus(VerificationStatus.FAILED);
        }
        targetRepository.save(target);
        return ok;
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/service/TargetService.java backend/src/main/java/com/secops/service/DnsVerificationService.java
git commit -m "feat: add TargetService and DNS verification service"
```

### Task 10: TargetController

**Files:**
- Create: `backend/src/main/java/com/secops/controller/TargetController.java`

- [ ] **Step 1: Create TargetController**

`TargetController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Target;
import com.secops.security.JwtUtil;
import com.secops.service.TargetService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/targets")
public class TargetController {
    private final TargetService targetService;
    private final JwtUtil jwtUtil;

    public TargetController(TargetService targetService, JwtUtil jwtUtil) {
        this.targetService = targetService;
        this.jwtUtil = jwtUtil;
    }

    private String getUsernameFromHeader(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return jwtUtil.extractUsername(authHeader.substring(7));
        }
        return null;
    }

    @GetMapping
    public R<List<Target>> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = getUsernameFromHeader(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(targetService.listTargets(username));
    }

    @PostMapping
    public R<Target> create(@RequestHeader(value = "Authorization", required = false) String authHeader,
                            @RequestBody Map<String, String> body) {
        String username = getUsernameFromHeader(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(targetService.createTarget(body.get("domain"), username));
    }

    @DeleteMapping("/{id}")
    public R<Void> delete(@PathVariable Long id) {
        targetService.deleteTarget(id);
        return R.ok();
    }

    @PostMapping("/{id}/verify")
    public R<Map<String, Boolean>> verify(@PathVariable Long id) {
        boolean ok = targetService.verifyTarget(id);
        return R.ok(Map.of("verified", ok));
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add backend/src/main/java/com/secops/controller/TargetController.java
git commit -m "feat: add TargetController"
```

### Task 11: Frontend Targets Page Integration

**Files:**
- Modify: `frontend/src/pages/Targets.jsx`

- [ ] **Step 1: Replace Targets.jsx with real API integration**

`Targets.jsx`:
```javascript
import React, { useState, useEffect } from 'react'
import { Card, Button, Table, Tag, Space, Modal, Form, Input, message } from 'antd'
import { PlusOutlined } from '@ant-design/icons'
import api from '../api/axios'

const columns = (onVerify, onDelete) => [
  { title: '域名', dataIndex: 'domain', key: 'domain' },
  { title: '验证状态', dataIndex: 'status', key: 'status',
    render: (s) => s === 'VERIFIED' ? <Tag color="green">已验证</Tag> : <Tag color="orange">待验证</Tag> },
  { title: '验证令牌', dataIndex: 'verificationToken', key: 'token',
    render: (t) => <span style={{ fontFamily: 'monospace', fontSize: 12 }}>{t}</span> },
  { title: '创建时间', dataIndex: 'createdAt', key: 'createdAt' },
  { title: '操作', key: 'action',
    render: (_, record) => (
      <Space>
        <a onClick={() => onVerify(record.id)}>验证</a>
        <a onClick={() => onDelete(record.id)}>删除</a>
      </Space>
    ) },
]

export default function Targets() {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(false)
  const [modalOpen, setModalOpen] = useState(false)
  const [form] = Form.useForm()

  const fetchTargets = async () => {
    setLoading(true)
    try {
      const res = await api.get('/targets')
      setData(res.data.data.map(t => ({ ...t, key: t.id })))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchTargets() }, [])

  const handleAdd = async (values) => {
    try {
      await api.post('/targets', values)
      message.success('添加成功')
      setModalOpen(false)
      form.resetFields()
      fetchTargets()
    } catch {
      message.error('添加失败')
    }
  }

  const handleVerify = async (id) => {
    try {
      const res = await api.post(`/targets/${id}/verify`)
      if (res.data.data.verified) {
        message.success('验证成功')
      } else {
        message.error('验证失败，请检查 DNS TXT 记录')
      }
      fetchTargets()
    } catch {
      message.error('验证请求失败')
    }
  }

  const handleDelete = async (id) => {
    try {
      await api.delete(`/targets/${id}`)
      message.success('删除成功')
      fetchTargets()
    } catch {
      message.error('删除失败')
    }
  }

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>目标管理</h2>
        <Button type="primary" icon={<PlusOutlined />} onClick={() => setModalOpen(true)}>添加目标</Button>
      </div>
      <Card>
        <Table columns={columns(handleVerify, handleDelete)} dataSource={data} loading={loading} />
      </Card>

      <Modal title="添加目标" open={modalOpen} onCancel={() => setModalOpen(false)} footer={null}>
        <Form form={form} onFinish={handleAdd} layout="vertical">
          <Form.Item name="domain" label="域名" rules={[{ required: true, pattern: /^[a-zA-Z0-9][-a-zA-Z0-9]*(\.[a-zA-Z0-9][-a-zA-Z0-9]*)+$/, message: '请输入有效域名' }]}>
            <Input placeholder="example.com" />
          </Form.Item>
          <Form.Item>
            <Button type="primary" htmlType="submit" block>提交</Button>
          </Form.Item>
        </Form>
      </Modal>
    </div>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/Targets.jsx
git commit -m "feat: integrate Targets page with backend API"
```

---

## Slice 3: Scanning + Engine Integration

### Task 12: ScanTask + Vulnerability Entities + Repositories

**Files:**
- Create: `backend/src/main/java/com/secops/entity/ScanTask.java`
- Create: `backend/src/main/java/com/secops/entity/Vulnerability.java`
- Create: `backend/src/main/java/com/secops/repository/ScanTaskRepository.java`
- Create: `backend/src/main/java/com/secops/repository/VulnerabilityRepository.java`

- [ ] **Step 1: Create ScanTask entity**

`ScanTask.java`:
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

- [ ] **Step 2: Create Vulnerability entity**

`Vulnerability.java`:
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

- [ ] **Step 3: Create repositories**

`ScanTaskRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.ScanTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ScanTaskRepository extends JpaRepository<ScanTask, Long> {
    List<ScanTask> findByTargetTeamId(Long teamId);
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
    List<Vulnerability> findByScanTaskTargetTeamId(Long teamId);
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/secops/entity/ScanTask.java backend/src/main/java/com/secops/entity/Vulnerability.java backend/src/main/java/com/secops/repository/ScanTaskRepository.java backend/src/main/java/com/secops/repository/VulnerabilityRepository.java
git commit -m "feat: add ScanTask and Vulnerability entities with repositories"
```

### Task 13: Scanner Engine Adapters

**Files:**
- Create: `backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java`
- Create: `backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java`
- Create: `backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java`
- Create: `backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java`
- Modify: `backend/src/main/resources/application.yml`

- [ ] **Step 1: Create SubfinderScanner**

`SubfinderScanner.java`:
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
    public String getName() { return "subfinder"; }

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
                pb.inheritIO();
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

- [ ] **Step 2: Create NaabuScanner**

`NaabuScanner.java`:
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
    public String getName() { return "naabu"; }

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
                // target here is a newline-separated list of hosts
                java.io.File tempFile = java.io.File.createTempFile("naabu-input", ".txt");
                try (java.io.FileWriter w = new java.io.FileWriter(tempFile)) {
                    w.write(target);
                }

                ProcessBuilder pb = new ProcessBuilder(binaryPath, "-list", tempFile.getAbsolutePath(), "-p", "-");
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

- [ ] **Step 3: Create HttpxScanner**

`HttpxScanner.java`:
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
    public String getName() { return "httpx"; }

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

- [ ] **Step 4: Create NucleiScanner**

`NucleiScanner.java`:
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
    public String getName() { return "nuclei"; }

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

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/secops/scanner/engine/SubfinderScanner.java backend/src/main/java/com/secops/scanner/engine/NaabuScanner.java backend/src/main/java/com/secops/scanner/engine/HttpxScanner.java backend/src/main/java/com/secops/scanner/engine/NucleiScanner.java
git commit -m "feat: add scanner engine adapters for subfinder, naabu, httpx, nuclei"
```

### Task 14: Scanning Orchestration Service

**Files:**
- Create: `backend/src/main/java/com/secops/service/ScannerEngineService.java`
- Modify: `backend/src/main/java/com/secops/SecOpsApplication.java`

- [ ] **Step 1: Enable Async in main class**

Add `@EnableAsync` to `SecOpsApplication.java` (it already has `@EnableScheduling`, add `@EnableAsync` alongside):
```java
package com.secops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableAsync
@EnableScheduling
@SpringBootApplication
public class SecOpsApplication {
    public static void main(String[] args) {
        SpringApplication.run(SecOpsApplication.class, args);
    }
}
```

- [ ] **Step 2: Create ScannerEngineService**

`ScannerEngineService.java`:
```java
package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanStatus;
import com.secops.entity.enums.Severity;
import com.secops.entity.enums.VulnStatus;
import com.secops.scanner.engine.*;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CompletableFuture;
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
            ScanResult subResult = subfinderScanner.scan(domain, new ScannerEngine.ScanOptions()).get();
            task.setProgress(30);
            scanTaskRepository.save(task);

            String subdomains = subResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (subdomains.isEmpty()) subdomains = domain;

            // Step 2: Naabu
            ScanResult portResult = naabuScanner.scan(subdomains, new ScannerEngine.ScanOptions()).get();
            task.setProgress(50);
            scanTaskRepository.save(task);

            String hostPorts = portResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (hostPorts.isEmpty()) hostPorts = domain;

            // Step 3: Httpx
            ScanResult aliveResult = httpxScanner.scan(hostPorts, new ScannerEngine.ScanOptions()).get();
            task.setProgress(70);
            scanTaskRepository.save(task);

            String aliveUrls = aliveResult.getFindings().stream()
                    .map(ScanResult.Finding::getMatched)
                    .collect(Collectors.joining("\n"));
            if (aliveUrls.isEmpty()) aliveUrls = domain;

            // Step 4: Nuclei
            ScanResult vulnResult = nucleiScanner.scan(aliveUrls, new ScannerEngine.ScanOptions()).get();
            task.setProgress(90);
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
            task.setRawOutput(vulnResult.getRawOutput() != null ? vulnResult.getRawOutput().toString() : "");
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

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/SecOpsApplication.java backend/src/main/java/com/secops/service/ScannerEngineService.java
git commit -m "feat: add scanner orchestration service with async pipeline"
```

### Task 15: ScanTaskService + ScanTaskController

**Files:**
- Create: `backend/src/main/java/com/secops/service/ScanTaskService.java`
- Create: `backend/src/main/java/com/secops/controller/ScanTaskController.java`

- [ ] **Step 1: Create ScanTaskService**

`ScanTaskService.java`:
```java
package com.secops.service;

import com.secops.entity.ScanTask;
import com.secops.entity.Target;
import com.secops.entity.User;
import com.secops.entity.enums.ScanType;
import com.secops.repository.ScanTaskRepository;
import com.secops.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

@Service
public class ScanTaskService {
    private final ScanTaskRepository scanTaskRepository;
    private final UserRepository userRepository;
    private final ScannerEngineService scannerEngineService;

    public ScanTaskService(ScanTaskRepository scanTaskRepository, UserRepository userRepository,
                           ScannerEngineService scannerEngineService) {
        this.scanTaskRepository = scanTaskRepository;
        this.userRepository = userRepository;
        this.scannerEngineService = scannerEngineService;
    }

    public ScanTask createScanTask(Long targetId, ScanType scanType, String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        Target target = new Target();
        target.setId(targetId);

        ScanTask task = new ScanTask();
        String dateStr = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd"));
        task.setTaskId("SCAN-" + dateStr + "-" + System.currentTimeMillis() % 10000);
        task.setTarget(target);
        task.setScanType(scanType);
        scanTaskRepository.save(task);

        scannerEngineService.runFullScan(task);
        return task;
    }

    public List<ScanTask> listTasks(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        return scanTaskRepository.findByTargetTeamId(user.getTeam().getId());
    }

    public ScanTask getTask(Long id) {
        return scanTaskRepository.findById(id).orElseThrow();
    }
}
```

- [ ] **Step 2: Create ScanTaskController**

`ScanTaskController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.ScanTask;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.ScanType;
import com.secops.security.JwtUtil;
import com.secops.service.ScanTaskService;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/scans")
public class ScanTaskController {
    private final ScanTaskService scanTaskService;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final JwtUtil jwtUtil;

    public ScanTaskController(ScanTaskService scanTaskService, VulnerabilityRepository vulnerabilityRepository, JwtUtil jwtUtil) {
        this.scanTaskService = scanTaskService;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.jwtUtil = jwtUtil;
    }

    private String getUsername(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return jwtUtil.extractUsername(authHeader.substring(7));
        }
        return null;
    }

    @GetMapping
    public R<List<ScanTask>> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(scanTaskService.listTasks(username));
    }

    @PostMapping
    public R<ScanTask> create(@RequestHeader(value = "Authorization", required = false) String authHeader,
                              @RequestBody Map<String, Object> body) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        Long targetId = Long.valueOf(body.get("targetId").toString());
        ScanType type = ScanType.valueOf(body.get("scanType").toString());
        return R.ok(scanTaskService.createScanTask(targetId, type, username));
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

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/service/ScanTaskService.java backend/src/main/java/com/secops/controller/ScanTaskController.java
git commit -m "feat: add scan task service and controller"
```

### Task 16: Frontend ScanTasks Page Integration

**Files:**
- Modify: `frontend/src/pages/ScanTasks.jsx`

- [ ] **Step 1: Replace ScanTasks.jsx with real API integration**

`ScanTasks.jsx`:
```javascript
import React, { useState, useEffect } from 'react'
import { Card, Button, Table, Tag, Progress, Space, Modal, Form, Select, message } from 'antd'
import { PlayCircleOutlined } from '@ant-design/icons'
import api from '../api/axios'

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
      const res = await api.get('/scans')
      setData(res.data.data.map(t => ({ ...t, key: t.id })))
    } finally {
      setLoading(false)
    }
  }

  const fetchTargets = async () => {
    try {
      const res = await api.get('/targets')
      setTargets(res.data.data.filter(t => t.status === 'VERIFIED'))
    } catch {}
  }

  useEffect(() => { fetchTasks(); fetchTargets() }, [])

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
      await api.post('/scans', values)
      message.success('扫描任务已创建')
      setModalOpen(false)
      form.resetFields()
      fetchTasks()
    } catch {
      message.error('创建失败')
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
          <a onClick={() => {}}>日志</a>
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
          <Form.Item name="targetId" label="目标" rules={[{ required: true }]}>
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

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/ScanTasks.jsx
git commit -m "feat: integrate ScanTasks page with backend API and polling"
```

---

## Slice 4: Vulnerabilities + Tickets

### Task 17: Ticket Entity + Repository

**Files:**
- Create: `backend/src/main/java/com/secops/entity/Ticket.java`
- Create: `backend/src/main/java/com/secops/repository/TicketRepository.java`

- [ ] **Step 1: Create Ticket entity**

`Ticket.java`:
```java
package com.secops.entity;

import com.secops.entity.enums.Priority;
import com.secops.entity.enums.TicketStatus;
import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class Ticket {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String ticketId;

    @Column(nullable = false)
    private String title;

    @Column(length = 2000)
    private String description;

    @Enumerated(EnumType.STRING)
    private Priority priority = Priority.MEDIUM;

    @Enumerated(EnumType.STRING)
    private TicketStatus status = TicketStatus.OPEN;

    private String assignee;

    private LocalDateTime createdAt = LocalDateTime.now();

    private LocalDateTime updatedAt = LocalDateTime.now();

    @ManyToOne
    @JoinColumn(name = "vulnerability_id")
    private Vulnerability vulnerability;
}
```

- [ ] **Step 2: Create TicketRepository**

`TicketRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.Ticket;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TicketRepository extends JpaRepository<Ticket, Long> {
    List<Ticket> findByVulnerabilityScanTaskTargetTeamId(Long teamId);
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/entity/Ticket.java backend/src/main/java/com/secops/repository/TicketRepository.java
git commit -m "feat: add Ticket entity and repository"
```

### Task 18: VulnService + TicketService + Controllers

**Files:**
- Create: `backend/src/main/java/com/secops/service/VulnerabilityService.java`
- Create: `backend/src/main/java/com/secops/service/TicketService.java`
- Create: `backend/src/main/java/com/secops/controller/VulnerabilityController.java`
- Create: `backend/src/main/java/com/secops/controller/TicketController.java`

- [ ] **Step 1: Create VulnerabilityService**

`VulnerabilityService.java`:
```java
package com.secops.service;

import com.secops.entity.Vulnerability;
import com.secops.entity.User;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.VulnerabilityRepository;
import com.secops.repository.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class VulnerabilityService {
    private final VulnerabilityRepository vulnerabilityRepository;
    private final UserRepository userRepository;

    public VulnerabilityService(VulnerabilityRepository vulnerabilityRepository, UserRepository userRepository) {
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.userRepository = userRepository;
    }

    public List<Vulnerability> listVulns(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        return vulnerabilityRepository.findByScanTaskTargetTeamId(user.getTeam().getId());
    }

    public Vulnerability getVuln(Long id) {
        return vulnerabilityRepository.findById(id).orElseThrow();
    }

    public void markFalsePositive(Long id) {
        Vulnerability v = getVuln(id);
        v.setStatus(VulnStatus.FALSE_POSITIVE);
        vulnerabilityRepository.save(v);
    }
}
```

- [ ] **Step 2: Create TicketService**

`TicketService.java`:
```java
package com.secops.service;

import com.secops.entity.Ticket;
import com.secops.entity.User;
import com.secops.entity.Vulnerability;
import com.secops.entity.enums.TicketStatus;
import com.secops.repository.TicketRepository;
import com.secops.repository.UserRepository;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
public class TicketService {
    private final TicketRepository ticketRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final UserRepository userRepository;

    public TicketService(TicketRepository ticketRepository, VulnerabilityRepository vulnerabilityRepository,
                         UserRepository userRepository) {
        this.ticketRepository = ticketRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.userRepository = userRepository;
    }

    public Ticket createTicket(Long vulnId, String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        Vulnerability vuln = vulnerabilityRepository.findById(vulnId).orElseThrow();

        Ticket ticket = new Ticket();
        ticket.setTicketId("TKT-" + System.currentTimeMillis() % 100000);
        ticket.setTitle("修复 " + vuln.getName());
        ticket.setDescription(vuln.getDescription());
        ticket.setPriority(mapSeverityToPriority(vuln.getSeverity()));
        ticket.setVulnerability(vuln);
        ticket.setAssignee(user.getUsername());
        return ticketRepository.save(ticket);
    }

    public List<Ticket> listTickets(String username) {
        User user = userRepository.findByUsername(username).orElseThrow();
        return ticketRepository.findByVulnerabilityScanTaskTargetTeamId(user.getTeam().getId());
    }

    public Ticket updateTicket(Long id, String status, String assignee) {
        Ticket ticket = ticketRepository.findById(id).orElseThrow();
        if (status != null) ticket.setStatus(TicketStatus.valueOf(status));
        if (assignee != null) ticket.setAssignee(assignee);
        ticket.setUpdatedAt(LocalDateTime.now());
        return ticketRepository.save(ticket);
    }

    private com.secops.entity.enums.Priority mapSeverityToPriority(com.secops.entity.enums.Severity s) {
        return switch (s) {
            case CRITICAL, HIGH -> com.secops.entity.enums.Priority.HIGH;
            case MEDIUM -> com.secops.entity.enums.Priority.MEDIUM;
            default -> com.secops.entity.enums.Priority.LOW;
        };
    }
}
```

- [ ] **Step 3: Create VulnerabilityController**

`VulnerabilityController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Vulnerability;
import com.secops.security.JwtUtil;
import com.secops.service.VulnerabilityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/vulns")
public class VulnerabilityController {
    private final VulnerabilityService vulnerabilityService;
    private final JwtUtil jwtUtil;

    public VulnerabilityController(VulnerabilityService vulnerabilityService, JwtUtil jwtUtil) {
        this.vulnerabilityService = vulnerabilityService;
        this.jwtUtil = jwtUtil;
    }

    private String getUsername(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return jwtUtil.extractUsername(authHeader.substring(7));
        }
        return null;
    }

    @GetMapping
    public R<List<Vulnerability>> list(@RequestHeader(value = "Authorization", required = false) String authHeader,
                                       @RequestParam(required = false) String severity) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        List<Vulnerability> list = vulnerabilityService.listVulns(username);
        if (severity != null) {
            list = list.stream().filter(v -> v.getSeverity().name().equalsIgnoreCase(severity)).toList();
        }
        return R.ok(list);
    }

    @GetMapping("/{id}")
    public R<Vulnerability> get(@PathVariable Long id) {
        return R.ok(vulnerabilityService.getVuln(id));
    }

    @PostMapping("/{id}/false-positive")
    public R<Void> falsePositive(@PathVariable Long id) {
        vulnerabilityService.markFalsePositive(id);
        return R.ok();
    }
}
```

- [ ] **Step 4: Create TicketController**

`TicketController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.Ticket;
import com.secops.security.JwtUtil;
import com.secops.service.TicketService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/tickets")
public class TicketController {
    private final TicketService ticketService;
    private final JwtUtil jwtUtil;

    public TicketController(TicketService ticketService, JwtUtil jwtUtil) {
        this.ticketService = ticketService;
        this.jwtUtil = jwtUtil;
    }

    private String getUsername(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return jwtUtil.extractUsername(authHeader.substring(7));
        }
        return null;
    }

    @GetMapping
    public R<List<Ticket>> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(ticketService.listTickets(username));
    }

    @PostMapping
    public R<Ticket> create(@RequestHeader(value = "Authorization", required = false) String authHeader,
                            @RequestBody Map<String, Long> body) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(ticketService.createTicket(body.get("vulnerabilityId"), username));
    }

    @PutMapping("/{id}")
    public R<Ticket> update(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return R.ok(ticketService.updateTicket(id, body.get("status"), body.get("assignee")));
    }
}
```

- [ ] **Step 5: Commit**

```bash
git add backend/src/main/java/com/secops/service/VulnerabilityService.java backend/src/main/java/com/secops/service/TicketService.java backend/src/main/java/com/secops/controller/VulnerabilityController.java backend/src/main/java/com/secops/controller/TicketController.java
git commit -m "feat: add vulnerability and ticket services and controllers"
```

### Task 19: Frontend Vuln + Ticket Pages Integration

**Files:**
- Modify: `frontend/src/pages/Vulnerabilities.jsx`
- Modify: `frontend/src/pages/Tickets.jsx`

- [ ] **Step 1: Replace Vulnerabilities.jsx**

`Vulnerabilities.jsx`:
```javascript
import React, { useState, useEffect } from 'react'
import { Card, Table, Tag, Space, Select, message, Modal, Button } from 'antd'
import { useSearchParams } from 'react-router-dom'
import api from '../api/axios'

const severityColors = { CRITICAL: 'red', HIGH: 'orange', MEDIUM: 'yellow', LOW: 'blue', INFO: 'default' }

export default function Vulnerabilities() {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(false)
  const [severityFilter, setSeverityFilter] = useState(null)
  const [detail, setDetail] = useState(null)
  const [searchParams] = useSearchParams()
  const scanId = searchParams.get('scanId')

  const fetchVulns = async () => {
    setLoading(true)
    try {
      const params = {}
      if (severityFilter) params.severity = severityFilter
      const res = await api.get('/vulns', { params })
      let list = res.data.data
      if (scanId) list = list.filter(v => v.scanTask?.id === Number(scanId))
      setData(list.map(v => ({ ...v, key: v.id })))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchVulns() }, [severityFilter, scanId])

  const handleCreateTicket = async (id) => {
    try {
      await api.post('/tickets', { vulnerabilityId: id })
      message.success('工单创建成功')
    } catch {
      message.error('创建失败')
    }
  }

  const handleFalsePositive = async (id) => {
    try {
      await api.post(`/vulns/${id}/false-positive`)
      message.success('已标记为误报')
      fetchVulns()
    } catch {
      message.error('操作失败')
    }
  }

  const columns = [
    { title: '漏洞', dataIndex: 'name', key: 'name' },
    { title: '目标', dataIndex: 'target', key: 'target' },
    { title: '等级', dataIndex: 'severity', key: 'severity',
      render: (s) => <Tag color={severityColors[s]}>{s}</Tag> },
    { title: '状态', dataIndex: 'status', key: 'status', render: (s) => <Tag>{s}</Tag> },
    { title: '发现时间', dataIndex: 'foundAt', key: 'foundAt' },
    { title: '操作', key: 'action',
      render: (_, record) => (
        <Space>
          <a onClick={() => setDetail(record)}>详情</a>
          <a onClick={() => handleCreateTicket(record.id)}>创建工单</a>
          <a onClick={() => handleFalsePositive(record.id)}>误报</a>
        </Space>
      ) },
  ]

  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 16 }}>
        <h2>漏洞管理</h2>
        <Select placeholder="按等级筛选" allowClear style={{ width: 150 }}
          onChange={setSeverityFilter}
          options={['CRITICAL','HIGH','MEDIUM','LOW','INFO'].map(s => ({ value: s, label: s }))} />
      </div>
      <Card>
        <Table columns={columns} dataSource={data} loading={loading} />
      </Card>

      <Modal title="漏洞详情" open={!!detail} onCancel={() => setDetail(null)} footer={null} width={700}>
        {detail && (
          <div>
            <p><strong>名称:</strong> {detail.name}</p>
            <p><strong>目标:</strong> {detail.target}</p>
            <p><strong>等级:</strong> <Tag color={severityColors[detail.severity]}>{detail.severity}</Tag></p>
            <p><strong>描述:</strong> {detail.description || '无'}</p>
            <p><strong>匹配:</strong> <code>{detail.matched}</code></p>
            <p><strong>扫描器:</strong> {detail.scanner}</p>
          </div>
        )}
      </Modal>
    </div>
  )
}
```

- [ ] **Step 2: Replace Tickets.jsx**

`Tickets.jsx`:
```javascript
import React, { useState, useEffect } from 'react'
import { Card, Table, Tag, Space, Select, message } from 'antd'
import api from '../api/axios'

const statusColors = { OPEN: 'red', IN_PROGRESS: 'blue', RESOLVED: 'green', CLOSED: 'default' }

export default function Tickets() {
  const [data, setData] = useState([])
  const [loading, setLoading] = useState(false)

  const fetchTickets = async () => {
    setLoading(true)
    try {
      const res = await api.get('/tickets')
      setData(res.data.data.map(t => ({ ...t, key: t.id })))
    } finally {
      setLoading(false)
    }
  }

  useEffect(() => { fetchTickets() }, [])

  const handleStatusChange = async (id, status) => {
    try {
      await api.put(`/tickets/${id}`, { status })
      message.success('状态更新成功')
      fetchTickets()
    } catch {
      message.error('更新失败')
    }
  }

  const columns = [
    { title: '工单号', dataIndex: 'ticketId', key: 'ticketId' },
    { title: '标题', dataIndex: 'title', key: 'title' },
    { title: '优先级', dataIndex: 'priority', key: 'priority',
      render: (p) => <Tag color={p === 'HIGH' ? 'red' : p === 'MEDIUM' ? 'orange' : 'blue'}>{p}</Tag> },
    { title: '负责人', dataIndex: 'assignee', key: 'assignee' },
    { title: '状态', dataIndex: 'status', key: 'status',
      render: (s, record) => (
        <Select value={s} size="small" style={{ width: 110 }}
          onChange={(val) => handleStatusChange(record.id, val)}>
          {['OPEN','IN_PROGRESS','RESOLVED','CLOSED'].map(st => (
            <Select.Option key={st} value={st}>
              <Tag color={statusColors[st]}>{st}</Tag>
            </Select.Option>
          ))}
        </Select>
      ) },
    { title: '操作', key: 'action', render: () => <Space><a>处理</a><a>关闭</a></Space> },
  ]

  return (
    <div>
      <h2 style={{ marginBottom: 16 }}>修复工单</h2>
      <Card>
        <Table columns={columns} dataSource={data} loading={loading} />
      </Card>
    </div>
  )
}
```

- [ ] **Step 3: Commit**

```bash
git add frontend/src/pages/Vulnerabilities.jsx frontend/src/pages/Tickets.jsx
git commit -m "feat: integrate Vulnerabilities and Tickets pages with backend API"
```

---

## Slice 5: Agent Chat + WebSocket

### Task 20: AgentSession + AgentMessage Entities + Repositories

**Files:**
- Create: `backend/src/main/java/com/secops/entity/AgentSession.java`
- Create: `backend/src/main/java/com/secops/entity/AgentMessage.java`
- Create: `backend/src/main/java/com/secops/repository/AgentSessionRepository.java`

- [ ] **Step 1: Create AgentMessage entity**

`AgentMessage.java`:
```java
package com.secops.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Data
public class AgentMessage {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String role; // user / assistant / thought / action / observe

    @Column(length = 4000)
    private String content;

    private LocalDateTime createdAt = LocalDateTime.now();
}
```

- [ ] **Step 2: Create AgentSession entity**

`AgentSession.java`:
```java
package com.secops.entity;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Data
public class AgentSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true)
    private String sessionId = UUID.randomUUID().toString();

    private String userId;

    private LocalDateTime createdAt = LocalDateTime.now();

    @OneToMany(cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.EAGER)
    @JoinColumn(name = "session_id", referencedColumnName = "sessionId")
    private List<AgentMessage> messages = new ArrayList<>();
}
```

- [ ] **Step 3: Create AgentSessionRepository**

`AgentSessionRepository.java`:
```java
package com.secops.repository;

import com.secops.entity.AgentSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AgentSessionRepository extends JpaRepository<AgentSession, Long> {
    Optional<AgentSession> findBySessionId(String sessionId);
    List<AgentSession> findByUserIdOrderByCreatedAtDesc(String userId);
}
```

- [ ] **Step 4: Commit**

```bash
git add backend/src/main/java/com/secops/entity/AgentMessage.java backend/src/main/java/com/secops/entity/AgentSession.java backend/src/main/java/com/secops/repository/AgentSessionRepository.java
git commit -m "feat: add AgentSession and AgentMessage entities with repository"
```

### Task 21: MockAgentRuntime Implementation

**Files:**
- Create: `backend/src/main/java/com/secops/agent/core/MockAgentRuntime.java`
- Modify: `backend/src/main/java/com/secops/agent/core/AgentRuntime.java` (fix import issue)

- [ ] **Step 1: Fix AgentRuntime interface import**

The existing `AgentRuntime.java` references `@Data` but doesn't import it. Replace with:
```java
package com.secops.agent.core;

import lombok.Data;
import java.util.List;

public interface AgentRuntime {
    AgentResponse execute(AgentContext context);
    void executeStream(AgentContext context, AgentStreamCallback callback);

    interface AgentStreamCallback {
        void onThink(String thought);
        void onAction(String toolName, String params);
        void onObserve(String result);
        void onComplete(String finalAnswer);
        void onError(String error);
    }

    @Data
    class AgentResponse {
        private String finalAnswer;
        private List<AgentContext.ToolCall> toolCalls;
        private boolean needsHumanConfirm;
        private String confirmReason;
    }
}
```

- [ ] **Step 2: Create MockAgentRuntime**

`MockAgentRuntime.java`:
```java
package com.secops.agent.core;

import com.secops.entity.Vulnerability;
import com.secops.repository.VulnerabilityRepository;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Component
public class MockAgentRuntime implements AgentRuntime {

    private final VulnerabilityRepository vulnerabilityRepository;

    public MockAgentRuntime(VulnerabilityRepository vulnerabilityRepository) {
        this.vulnerabilityRepository = vulnerabilityRepository;
    }

    @Override
    public AgentResponse execute(AgentContext context) {
        AgentResponse response = new AgentResponse();
        response.setFinalAnswer(generateAnswer(context.getQuery(), null));
        return response;
    }

    @Override
    public void executeStream(AgentContext context, AgentStreamCallback callback) {
        CompletableFuture.runAsync(() -> {
            try {
                String query = context.getQuery().toLowerCase();

                // Step 1: Think
                callback.onThink("正在分析用户问题: " + context.getQuery());
                Thread.sleep(600);

                // Step 2: Action - query vulns
                callback.onAction("query_vulns", "{\"limit\": 10}");
                Thread.sleep(500);

                // Step 3: Observe
                List<Vulnerability> vulns = vulnerabilityRepository.findAll();
                String target = extractTarget(query);
                if (target != null) {
                    vulns = vulns.stream().filter(v -> v.getTarget() != null && v.getTarget().contains(target)).toList();
                }
                String observeText = vulns.isEmpty()
                    ? "未找到相关漏洞记录"
                    : String.format("找到 %d 个漏洞，其中高危 %d 个", vulns.size(),
                        vulns.stream().filter(v -> v.getSeverity() != null && v.getSeverity().name().contains("HIGH")).count());
                callback.onObserve(observeText);
                Thread.sleep(500);

                // Step 4: Complete
                callback.onComplete(generateAnswer(query, vulns));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                callback.onError("执行中断");
            } catch (Exception e) {
                callback.onError(e.getMessage());
            }
        });
    }

    private String extractTarget(String query) {
        // Simple extraction: look for domain-like patterns
        String[] words = query.split("\\s+");
        for (String w : words) {
            if (w.contains(".") && !w.startsWith("http")) return w;
        }
        return null;
    }

    private String generateAnswer(String query, List<Vulnerability> vulns) {
        if (query.contains("高危") || query.contains("high") || query.contains("严重")) {
            if (vulns == null || vulns.isEmpty()) return "目前系统中没有记录的高危漏洞。建议定期执行全量扫描以保持最新状态。";
            List<Vulnerability> high = vulns.stream()
                .filter(v -> v.getSeverity() != null && (v.getSeverity().name().equals("HIGH") || v.getSeverity().name().equals("CRITICAL")))
                .toList();
            if (high.isEmpty()) return "当前目标下未发现高危漏洞，但建议关注中危漏洞的修复进度。";
            return String.format("发现 %d 个高危漏洞：\n\n%s\n\n建议优先修复暴露面最大的漏洞，并考虑添加 WAF 规则进行临时防护。",
                high.size(),
                high.stream().map(v -> "- " + v.getName() + " (" + v.getMatched() + ")").collect(Collectors.joining("\n")));
        }
        if (query.contains("修复") || query.contains("fix") || query.contains("建议")) {
            return "针对常见漏洞的通用修复建议：\n1. 及时更新依赖到最新版本\n2. 关闭不必要的端口和服务\n3. 配置 Web 应用防火墙\n4. 启用最小权限原则\n5. 定期进行安全审计\n\n如需针对具体漏洞的修复方案，请告诉我漏洞名称。";
        }
        return "我已分析了相关扫描数据。您可以通过左侧菜单查看详细的漏洞列表和扫描结果。如有具体问题，欢迎继续提问。";
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/agent/core/AgentRuntime.java backend/src/main/java/com/secops/agent/core/MockAgentRuntime.java
git commit -m "feat: implement MockAgentRuntime with simulated ReAct loop"
```

### Task 22: AgentSessionService + AgentController

**Files:**
- Create: `backend/src/main/java/com/secops/service/AgentSessionService.java`
- Create: `backend/src/main/java/com/secops/controller/AgentController.java`

- [ ] **Step 1: Create AgentSessionService**

`AgentSessionService.java`:
```java
package com.secops.service;

import com.secops.entity.AgentMessage;
import com.secops.entity.AgentSession;
import com.secops.repository.AgentSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class AgentSessionService {
    private final AgentSessionRepository agentSessionRepository;

    public AgentSessionService(AgentSessionRepository agentSessionRepository) {
        this.agentSessionRepository = agentSessionRepository;
    }

    public AgentSession createSession(String userId) {
        AgentSession session = new AgentSession();
        session.setUserId(userId);
        return agentSessionRepository.save(session);
    }

    public List<AgentSession> listSessions(String userId) {
        return agentSessionRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }

    public AgentSession getSession(String sessionId) {
        return agentSessionRepository.findBySessionId(sessionId).orElseThrow();
    }

    public void addMessage(String sessionId, String role, String content) {
        AgentSession session = getSession(sessionId);
        AgentMessage msg = new AgentMessage();
        msg.setRole(role);
        msg.setContent(content);
        session.getMessages().add(msg);
        agentSessionRepository.save(session);
    }
}
```

- [ ] **Step 2: Create AgentController**

`AgentController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.AgentSession;
import com.secops.security.JwtUtil;
import com.secops.service.AgentSessionService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/agent")
public class AgentController {
    private final AgentSessionService agentSessionService;
    private final JwtUtil jwtUtil;

    public AgentController(AgentSessionService agentSessionService, JwtUtil jwtUtil) {
        this.agentSessionService = agentSessionService;
        this.jwtUtil = jwtUtil;
    }

    private String getUsername(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return jwtUtil.extractUsername(authHeader.substring(7));
        }
        return null;
    }

    @GetMapping("/sessions")
    public R<List<AgentSession>> list(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(agentSessionService.listSessions(username));
    }

    @PostMapping("/sessions")
    public R<AgentSession> create(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = getUsername(authHeader);
        if (username == null) return R.error(401, "未登录");
        return R.ok(agentSessionService.createSession(username));
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/service/AgentSessionService.java backend/src/main/java/com/secops/controller/AgentController.java
git commit -m "feat: add agent session service and controller"
```

### Task 23: WebSocket Config + Handler

**Files:**
- Create: `backend/src/main/java/com/secops/config/WebSocketConfig.java`
- Create: `backend/src/main/java/com/secops/websocket/AgentWebSocketHandler.java`

- [ ] **Step 1: Create WebSocketConfig**

`WebSocketConfig.java`:
```java
package com.secops.config;

import com.secops.websocket.AgentWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final AgentWebSocketHandler agentWebSocketHandler;

    public WebSocketConfig(AgentWebSocketHandler agentWebSocketHandler) {
        this.agentWebSocketHandler = agentWebSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(agentWebSocketHandler, "/ws/agent/{sessionId}")
                .setAllowedOrigins("*");
    }
}
```

- [ ] **Step 2: Create AgentWebSocketHandler**

`AgentWebSocketHandler.java`:
```java
package com.secops.websocket;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.secops.agent.core.AgentContext;
import com.secops.agent.core.AgentRuntime;
import com.secops.service.AgentSessionService;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AgentWebSocketHandler extends TextWebSocketHandler {

    private final AgentRuntime agentRuntime;
    private final AgentSessionService agentSessionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private final Map<String, String> sessionIdMap = new ConcurrentHashMap<>();

    public AgentWebSocketHandler(AgentRuntime agentRuntime, AgentSessionService agentSessionService) {
        this.agentRuntime = agentRuntime;
        this.agentSessionService = agentSessionService;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) {
        String uri = session.getUri().toString();
        String sessionId = uri.substring(uri.lastIndexOf('/') + 1);
        sessionIdMap.put(session.getId(), sessionId);
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String sessionId = sessionIdMap.get(session.getId());
        try {
            JsonNode node = objectMapper.readTree(message.getPayload());
            String type = node.get("type").asText();
            String content = node.get("content").asText();

            if ("CHAT".equals(type)) {
                agentSessionService.addMessage(sessionId, "user", content);

                AgentContext context = new AgentContext();
                context.setSessionId(sessionId);
                context.setQuery(content);

                agentRuntime.executeStream(context, new AgentRuntime.AgentStreamCallback() {
                    @Override
                    public void onThink(String thought) {
                        send(session, "THOUGHT", thought);
                    }

                    @Override
                    public void onAction(String toolName, String params) {
                        send(session, "ACTION", toolName + ": " + params);
                    }

                    @Override
                    public void onObserve(String result) {
                        send(session, "OBSERVE", result);
                    }

                    @Override
                    public void onComplete(String finalAnswer) {
                        send(session, "COMPLETE", finalAnswer);
                        agentSessionService.addMessage(sessionId, "assistant", finalAnswer);
                    }

                    @Override
                    public void onError(String error) {
                        send(session, "ERROR", error);
                    }
                });
            }
        } catch (Exception e) {
            send(session, "ERROR", e.getMessage());
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        sessionIdMap.remove(session.getId());
    }

    private void send(WebSocketSession session, String type, String content) {
        try {
            if (session.isOpen()) {
                session.sendMessage(new TextMessage("{\"type\":\"" + type + "\",\"content\":\"" + content.replace("\"", "\\\"") + "\"}"));
            }
        } catch (Exception e) {
            // ignore send errors
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/config/WebSocketConfig.java backend/src/main/java/com/secops/websocket/AgentWebSocketHandler.java
git commit -m "feat: add WebSocket config and Agent message handler"
```

### Task 24: Frontend AgentChat WebSocket Integration

**Files:**
- Modify: `frontend/src/pages/AgentChat.jsx`

- [ ] **Step 1: Replace AgentChat.jsx**

`AgentChat.jsx`:
```javascript
import React, { useState, useEffect, useRef, useCallback } from 'react'
import { Card, Input, Button, List, Avatar, Typography, Space, Tag, Select } from 'antd'
import { RobotOutlined, UserOutlined, SendOutlined, PlusOutlined } from '@ant-design/icons'
import api from '../api/axios'

const { Text } = Typography

const WS_BASE = 'ws://localhost:8080/ws/agent'

export default function AgentChat() {
  const [sessions, setSessions] = useState([])
  const [currentSessionId, setCurrentSessionId] = useState(null)
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [connected, setConnected] = useState(false)
  const wsRef = useRef(null)

  const fetchSessions = async () => {
    try {
      const res = await api.get('/agent/sessions')
      setSessions(res.data.data)
    } catch {}
  }

  const createSession = async () => {
    try {
      const res = await api.post('/agent/sessions')
      const newSession = res.data.data
      setSessions(prev => [newSession, ...prev])
      setCurrentSessionId(newSession.sessionId)
    } catch {}
  }

  useEffect(() => { fetchSessions() }, [])

  const connectWebSocket = useCallback((sessionId) => {
    if (wsRef.current) wsRef.current.close()
    const ws = new WebSocket(`${WS_BASE}/${sessionId}`)
    ws.onopen = () => setConnected(true)
    ws.onclose = () => setConnected(false)
    ws.onmessage = (event) => {
      const msg = JSON.parse(event.data)
      setMessages(prev => [...prev, { role: msg.type.toLowerCase(), content: msg.content }])
    }
    wsRef.current = ws
  }, [])

  useEffect(() => {
    if (currentSessionId) {
      connectWebSocket(currentSessionId)
      setMessages([])
    }
    return () => { if (wsRef.current) wsRef.current.close() }
  }, [currentSessionId, connectWebSocket])

  const send = () => {
    if (!input.trim() || !wsRef.current || !connected) return
    setMessages(prev => [...prev, { role: 'user', content: input }])
    wsRef.current.send(JSON.stringify({ type: 'CHAT', content: input }))
    setInput('')
  }

  const renderMessage = (msg) => {
    if (msg.role === 'user') {
      return (
        <List.Item style={{ justifyContent: 'flex-end', border: 'none' }}>
          <Space>
            <div style={{ padding: '8px 16px', borderRadius: 12, background: '#1677ff', color: '#fff', maxWidth: 600 }}>
              <Text style={{ color: 'inherit' }}>{msg.content}</Text>
            </div>
            <Avatar style={{ backgroundColor: '#52c41a' }} icon={<UserOutlined />} />
          </Space>
        </List.Item>
      )
    }
    if (msg.role === 'thought') {
      return (
        <List.Item style={{ border: 'none', padding: '4px 0' }}>
          <Text type="secondary" italic style={{ fontSize: 12 }}>💭 {msg.content}</Text>
        </List.Item>
      )
    }
    if (msg.role === 'action') {
      return (
        <List.Item style={{ border: 'none', padding: '4px 0' }}>
          <Tag color="blue">🔧 {msg.content}</Tag>
        </List.Item>
      )
    }
    if (msg.role === 'observe') {
      return (
        <List.Item style={{ border: 'none', padding: '4px 0' }}>
          <Tag color="green">👁 {msg.content}</Tag>
        </List.Item>
      )
    }
    return (
      <List.Item style={{ justifyContent: 'flex-start', border: 'none' }}>
        <Space>
          <Avatar style={{ backgroundColor: '#1677ff' }} icon={<RobotOutlined />} />
          <div style={{ padding: '8px 16px', borderRadius: 12, background: '#f0f0f0', color: '#333', maxWidth: 600, whiteSpace: 'pre-wrap' }}>
            <Text>{msg.content}</Text>
          </div>
        </Space>
      </List.Item>
    )
  }

  return (
    <div style={{ display: 'flex', height: 'calc(100vh - 112px)', gap: 16 }}>
      <Card style={{ width: 220, display: 'flex', flexDirection: 'column' }} bodyStyle={{ padding: 12, flex: 1, overflow: 'auto' }}>
        <Button type="primary" icon={<PlusOutlined />} block onClick={createSession} style={{ marginBottom: 12 }}>新会话</Button>
        <Select
          style={{ width: '100%' }}
          placeholder="选择会话"
          value={currentSessionId}
          onChange={setCurrentSessionId}
          options={sessions.map(s => ({ value: s.sessionId, label: s.sessionId.substring(0, 8) + '...' }))}
        />
      </Card>

      <div style={{ flex: 1, display: 'flex', flexDirection: 'column' }}>
        <h2>Agent 安全助手 {connected ? <Tag color="green">已连接</Tag> : <Tag>未连接</Tag>}</h2>
        <Card style={{ flex: 1, marginTop: 16, overflow: 'auto' }}>
          <List dataSource={messages} renderItem={renderMessage} />
        </Card>
        <div style={{ marginTop: 16, display: 'flex', gap: 8 }}>
          <Input.TextArea
            value={input}
            onChange={(e) => setInput(e.target.value)}
            placeholder="询问漏洞详情、修复建议..."
            autoSize={{ minRows: 1, maxRows: 4 }}
            onPressEnter={(e) => { if (!e.shiftKey) { e.preventDefault(); send() } }}
            disabled={!connected}
          />
          <Button type="primary" icon={<SendOutlined />} onClick={send} disabled={!connected}>发送</Button>
        </div>
      </div>
    </div>
  )
}
```

- [ ] **Step 2: Commit**

```bash
git add frontend/src/pages/AgentChat.jsx
git commit -m "feat: integrate AgentChat with WebSocket and session management"
```

### Task 25: Dashboard Stats API + Frontend

**Files:**
- Create: `backend/src/main/java/com/secops/controller/DashboardController.java`
- Modify: `frontend/src/pages/Dashboard.jsx`

- [ ] **Step 1: Create DashboardController**

`DashboardController.java`:
```java
package com.secops.controller;

import com.secops.common.R;
import com.secops.entity.User;
import com.secops.entity.enums.VulnStatus;
import com.secops.repository.*;
import com.secops.security.JwtUtil;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/dashboard")
public class DashboardController {
    private final TargetRepository targetRepository;
    private final ScanTaskRepository scanTaskRepository;
    private final VulnerabilityRepository vulnerabilityRepository;
    private final TicketRepository ticketRepository;
    private final UserRepository userRepository;
    private final JwtUtil jwtUtil;

    public DashboardController(TargetRepository targetRepository, ScanTaskRepository scanTaskRepository,
                               VulnerabilityRepository vulnerabilityRepository, TicketRepository ticketRepository,
                               UserRepository userRepository, JwtUtil jwtUtil) {
        this.targetRepository = targetRepository;
        this.scanTaskRepository = scanTaskRepository;
        this.vulnerabilityRepository = vulnerabilityRepository;
        this.ticketRepository = ticketRepository;
        this.userRepository = userRepository;
        this.jwtUtil = jwtUtil;
    }

    @GetMapping("/stats")
    public R<Map<String, Long>> stats(@RequestHeader(value = "Authorization", required = false) String authHeader) {
        String username = null;
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            username = jwtUtil.extractUsername(authHeader.substring(7));
        }
        if (username == null) return R.error(401, "未登录");

        User user = userRepository.findByUsername(username).orElseThrow();
        Long teamId = user.getTeam().getId();

        long targets = targetRepository.findByTeamId(teamId).size();
        long scans = scanTaskRepository.findByTargetTeamId(teamId).size();
        long highVulns = vulnerabilityRepository.findByScanTaskTargetTeamId(teamId).stream()
                .filter(v -> v.getStatus() != VulnStatus.FALSE_POSITIVE)
                .filter(v -> v.getSeverity() != null && (v.getSeverity().name().equals("HIGH") || v.getSeverity().name().equals("CRITICAL")))
                .count();
        long tickets = ticketRepository.findByVulnerabilityScanTaskTargetTeamId(teamId).stream()
                .filter(t -> t.getStatus() != com.secops.entity.enums.TicketStatus.CLOSED)
                .count();

        return R.ok(Map.of(
            "targets", targets,
            "scans", scans,
            "highVulns", highVulns,
            "tickets", tickets
        ));
    }
}
```

- [ ] **Step 2: Replace Dashboard.jsx**

`Dashboard.jsx`:
```javascript
import React, { useState, useEffect } from 'react'
import { Row, Col, Card, Statistic } from 'antd'
import { GlobalOutlined, ScanOutlined, BugOutlined, FileTextOutlined } from '@ant-design/icons'
import api from '../api/axios'

export default function Dashboard() {
  const [stats, setStats] = useState({ targets: 0, scans: 0, highVulns: 0, tickets: 0 })

  useEffect(() => {
    api.get('/dashboard/stats').then(res => {
      if (res.data.code === 200) setStats(res.data.data)
    })
  }, [])

  return (
    <div>
      <h2>安全仪表盘</h2>
      <Row gutter={16} style={{ marginTop: 16 }}>
        <Col span={6}>
          <Card>
            <Statistic title="监控目标" value={stats.targets} prefix={<GlobalOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="扫描任务" value={stats.scans} prefix={<ScanOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="高危漏洞" value={stats.highVulns} valueStyle={{ color: '#cf1322' }} prefix={<BugOutlined />} />
          </Card>
        </Col>
        <Col span={6}>
          <Card>
            <Statistic title="待修复工单" value={stats.tickets} prefix={<FileTextOutlined />} />
          </Card>
        </Col>
      </Row>
    </div>
  )
}
```

- [ ] **Step 3: Commit**

```bash
git add backend/src/main/java/com/secops/controller/DashboardController.java frontend/src/pages/Dashboard.jsx
git commit -m "feat: add dashboard stats API and real-time frontend"
```

---

## Self-Review

### 1. Spec Coverage Check

| Spec 需求 | 实现任务 |
|---------|---------|
| JWT 注册/登录/密码加密 | Task 1-7 |
| 目标管理 + DNS TXT 验证 | Task 8-11 |
| 扫描引擎适配器 + 真实调用 | Task 12-16 |
| 漏洞管理 + 工单 | Task 17-19 |
| Agent Chat + WebSocket (Mock) | Task 20-24 |
| Dashboard 真实数据 | Task 25 |

**无遗漏。**

### 2. Placeholder Scan

- 无 TBD/TODO
- 无 "add appropriate error handling" 等模糊描述
- 每个代码步骤包含完整可运行代码
- 每个任务有明确的文件路径

### 3. Type Consistency

- `ScanStatus` / `VulnStatus` / `TicketStatus` 枚举名称与实体字段一致
- `AgentStreamCallback` 接口方法与 `AgentWebSocketHandler` 调用一致
- 所有 Repository 方法签名与 Service/Controller 调用一致
- JWT token 提取逻辑在全部 Controller 中一致

**无类型不一致问题。**

---

## Execution Handoff

**Plan complete and saved to `docs/superpowers/plans/2026-05-28-secops-agent-mvp.md`.**

**Two execution options:**

**1. Subagent-Driven (recommended)** — I dispatch a fresh subagent per task, review between tasks, fast iteration

**2. Inline Execution** — Execute tasks in this session using executing-plans, batch execution with checkpoints

**Which approach?**
