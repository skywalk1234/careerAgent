package group.career_backend.security;

import group.career_backend.exception.UnauthorizedException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class JwtUserInterceptor implements HandlerInterceptor {
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtTool jwtTool;

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        String authorization = request.getHeader("Authorization");
        if (!StringUtils.hasText(authorization)) {
            log.warn("[JWT认证] 请求未携带Authorization，使用兜底用户, method={}, uri={}, userId={}",
                    request.getMethod(), request.getRequestURI(), UserContext.DEFAULT_USER_ID);
            return true;
        }
        if (!authorization.startsWith(BEARER_PREFIX)) {
            log.warn("[JWT认证] Authorization格式错误, method={}, uri={}",
                    request.getMethod(), request.getRequestURI());
            throw new UnauthorizedException("Authorization格式错误");
        }

        String token = authorization.substring(BEARER_PREFIX.length()).trim();
        if (!StringUtils.hasText(token)) {
            throw new UnauthorizedException("Authorization中缺少token");
        }
        Long userId = jwtTool.parseToken(token);
        request.setAttribute(UserContext.USER_ID_ATTRIBUTE, userId);
        log.info("[JWT认证] Token解析成功, method={}, uri={}, userId={}",
                request.getMethod(), request.getRequestURI(), userId);
        return true;
    }
}
