package com.online_ordering_system.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.online_ordering_system.domain.User;
import com.online_ordering_system.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
// 🌟 核心补齐：必须加上跨域注解，否则浏览器会拦截登录请求
@CrossOrigin(origins = "*", allowedHeaders = "*", methods = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT, RequestMethod.DELETE})
public class AuthController {

    private final UserMapper userMapper;

    @PostMapping("/login")
    public ResponseEntity<Map<String, Object>> login(@RequestBody Map<String, String> params) {

        String phone = params.get("phone");
        if (phone == null || phone.trim().isEmpty()) {
            Map<String, Object> error = new HashMap<>();
            error.put("message", "手机号不能为空");
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(error);
        }

        // 查询用户
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getPhone, phone)
        );

        // 不存在则自动注册
        if (user == null) {
            user = new User();
            user.setUserId(UUID.randomUUID().toString().replace("-", ""));
            user.setPhone(phone);
            user.setUsername("用户" + phone.substring(Math.max(0, phone.length() - 4)));
            userMapper.insert(user);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("token", "mock-jwt-" + user.getUserId());
        result.put("userId", user.getUserId());
        result.put("username", user.getUsername());
        result.put("phone", user.getPhone());

        return ResponseEntity.ok(result);
    }
}