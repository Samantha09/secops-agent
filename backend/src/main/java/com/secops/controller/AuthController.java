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
