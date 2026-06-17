package com.online_ordering_system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.User;
import com.online_ordering_system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/auth")
@CrossOrigin(origins = "*")
@RequiredArgsConstructor
public class AuthController {

    private final UserMapper userMapper;

    @PostMapping("/login")
    public Map<String, Object> login(@RequestBody Map<String, String> loginData) {
        String phone = loginData.get("phone");

        User user = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getPhone, phone));
        if (user == null) {
            user = new User();
            user.setPhone(phone);
            user.setUsername("美味食客_" + phone.substring(7));
            userMapper.insert(user);
        }

        Map<String, Object> response = new HashMap<>();
        response.put("token", "mock-jwt-token-for-" + phone);
        response.put("userId", user.getUserId());
        response.put("username", user.getUsername());
        return response;
    }
}
