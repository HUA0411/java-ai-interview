package com.guohua.interview.auth;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guohua.interview.auth.entity.User;
import com.guohua.interview.auth.mapper.UserMapper;
import com.guohua.interview.common.BizException;
import lombok.RequiredArgsConstructor;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.Map;

/**
 * 用户服务：注册、登录
 */
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;
    private final JwtUtil jwtUtil;
    private final BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

    /** 注册：用户名唯一校验 + BCrypt 加密存储 */
    public Map<String, Object> register(String username, String password) {
        Long exists = userMapper.selectCount(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (exists > 0) {
            throw BizException.badRequest("用户名已被注册");
        }

        User user = new User();
        user.setUsername(username);
        user.setPasswordHash(encoder.encode(password));
        userMapper.insert(user);

        return buildAuthResult(user);
    }

    /** 登录：校验密码后签发 JWT */
    public Map<String, Object> login(String username, String password) {
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>().eq(User::getUsername, username));
        if (user == null || !encoder.matches(password, user.getPasswordHash())) {
            throw BizException.unauthorized("用户名或密码错误");
        }
        return buildAuthResult(user);
    }

    private Map<String, Object> buildAuthResult(User user) {
        Map<String, Object> data = new HashMap<>();
        data.put("token", jwtUtil.generate(user.getId(), user.getUsername()));
        data.put("userId", user.getId());
        data.put("username", user.getUsername());
        return data;
    }
}
