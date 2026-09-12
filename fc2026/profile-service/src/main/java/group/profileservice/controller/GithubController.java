package group.profileservice.controller;

import group.common.Result;
import group.profileservice.dto.GithubAuthUrlResponse;
import group.profileservice.dto.GithubCallbackResponse;
import group.profileservice.dto.GithubSummaryResponse;
import group.profileservice.service.GithubService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletRequest;

/**
 * GitHub开源授权控制器
 * 处理GitHub OAuth授权相关接口
 */
@RestController
@RequestMapping("/users/me/open-source")
@RequiredArgsConstructor
@Slf4j
public class GithubController {

    private final GithubService githubService;

    /**
     * 4.4.1 获取授权跳转地址
     * GET /users/me/open-source/auth-url
     *
     * @param provider 提供商（github）
     * @param request HTTP请求
     * @return 授权URL和state
     */
    @GetMapping("/auth-url")
    public Result<GithubAuthUrlResponse> getAuthUrl(
            @RequestParam String provider,
            HttpServletRequest request) {

        Long userId = getUserIdFromRequest(request);

        if (!"github".equalsIgnoreCase(provider)) {
            return Result.error(400, "不支持的提供商: " + provider);
        }

        String state = generateState();
        String authUrl = githubService.generateAuthUrl(userId);

        GithubAuthUrlResponse response = new GithubAuthUrlResponse();
        response.setProvider("github");
        response.setState(state);
        response.setAuthUrl(authUrl);

        log.info("用户 {} 请求GitHub授权URL", userId);
        return Result.success(response, "OK");
    }

    /**
     * 4.4.2 授权回调换取统计结果
     * GET /users/me/open-source/callback
     *
     * @param provider 提供商（github）
     * @param state 授权态参数
     * @param code 平台回调授权码
     * @param request HTTP请求
     * @return 授权成功后的统计结果
     */
    @GetMapping("/callback")
    public Result<GithubCallbackResponse> callback(
            @RequestParam String provider,
            @RequestParam String state,
            @RequestParam String code,
            HttpServletRequest request) {

        Long userId = getUserIdFromRequest(request);

        if (!"github".equalsIgnoreCase(provider)) {
            return Result.error(400, "不支持的提供商: " + provider);
        }

        log.info("用户 {} GitHub授权回调，code: {}, state: {}", userId, code, state);

        GithubCallbackResponse response = githubService.handleCallback(userId, code, state);
        return Result.success(response, "授权成功");
    }

    /**
     * 4.4.3 获取开源统计与加分摘要
     * GET /users/me/open-source/summary
     *
     * @param request HTTP请求
     * @return 当前用户开源授权摘要
     */
    @GetMapping("/summary")
    public Result<GithubSummaryResponse> getSummary(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);

        log.info("用户 {} 请求GitHub授权摘要", userId);

        GithubSummaryResponse response = githubService.getSummary(userId);
        return Result.success(response, "OK");
    }

    /**
     * 4.4.4 解绑授权
     * POST /users/me/open-source/unbind
     *
     * @param request HTTP请求
     * @return 解绑结果
     */
    @PostMapping("/unbind")
    public Result<Void> unbind(HttpServletRequest request) {
        Long userId = getUserIdFromRequest(request);

        log.info("用户 {} 请求解绑GitHub授权", userId);

        githubService.unbind(userId);
        return Result.success(null, "解绑成功");
    }

    /**
     * 从请求中获取用户ID
     */
    private Long getUserIdFromRequest(HttpServletRequest request) {
        String userIdHeader = request.getHeader("X-User-Id");
        if (userIdHeader != null) {
            try {
                return Long.parseLong(userIdHeader);
            } catch (NumberFormatException e) {
                log.warn("Invalid user ID header: {}", userIdHeader);
            }
        }
        return 111L;
    }

    /**
     * 生成随机state
     */
    private String generateState() {
        return java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
