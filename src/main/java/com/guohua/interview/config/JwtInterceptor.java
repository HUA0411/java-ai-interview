package com.guohua.interview.config;

import com.guohua.interview.auth.JwtUtil;
import com.guohua.interview.common.BizException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器：校验 Authorization: Bearer <token>，通过后把 userId 放入 request attribute
 */
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    public static final String ATTR_USER_ID = "userId";

    private final JwtUtil jwtUtil;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 放行 CORS 预检请求
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            throw BizException.unauthorized("未登录或 token 缺失");
        }
        try {
            Long userId = jwtUtil.parseUserId(header.substring(7));
            request.setAttribute(ATTR_USER_ID, userId);
            return true;
        } catch (Exception e) {
            throw BizException.unauthorized("token 无效或已过期");
        }
    }
}
