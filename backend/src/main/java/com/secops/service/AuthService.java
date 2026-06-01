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
