package com.guohua.interview.auth;

import com.guohua.interview.common.Result;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 认证接口：注册 / 登录
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Validated
public class AuthController {

    private final UserService userService;

    public record RegisterRequest(
            @NotBlank(message = "用户名不能为空")
            @Size(min = 3, max = 20, message = "用户名长度需在 3-20 之间")
            String username,
            @NotBlank(message = "密码不能为空")
            @Size(min = 6, max = 32, message = "密码长度需在 6-32 之间")
            String password) {
    }

    public record LoginRequest(
            @NotBlank(message = "用户名不能为空") String username,
            @NotBlank(message = "密码不能为空") String password) {
    }

    @PostMapping("/register")
    public Result<Map<String, Object>> register(@Validated @RequestBody RegisterRequest req) {
        log.info("用户注册: {}", req.username());
        return Result.ok(userService.register(req.username(), req.password()));
    }

    @PostMapping("/login")
    public Result<Map<String, Object>> login(@Validated @RequestBody LoginRequest req) {
        return Result.ok(userService.login(req.username(), req.password()));
    }
}
