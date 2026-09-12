package group.resumeparserservice.config;/* I love coding */

import cn.hutool.core.util.StrUtil;
import group.utils.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * 注意：resume-parser-service 是 Spring Boot 3.3（Spring MVC 6），
 * 拦截器必须实现 jakarta.servlet 版本接口。
 * 不能直接用 common-service 里 Boot 2.7 编译的 javax 版拦截器，
 * 否则 preHandle 签名对不上、永远不会被调用，UserContext 恒为 null。
 */
@Component
public class UserInfoInterceptor implements HandlerInterceptor {

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) throws Exception {
        String userInfo = request.getHeader("user-info");
        if (StrUtil.isNotBlank(userInfo)) {
            UserContext.setUser(Long.valueOf(userInfo));
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response, Object handler, Exception ex) throws Exception {
        UserContext.removeUser();
    }
}
