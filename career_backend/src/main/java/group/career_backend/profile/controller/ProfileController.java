package group.career_backend.profile.controller;

import group.career_backend.common.Result;
import group.career_backend.profile.service.ProfileService;
import group.career_backend.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProfileController {
    private final ProfileService profileService;

    @RequestMapping("/users/me/profile/parse-jobs/{parseJobId}")
    public Result<?> queryResume(@PathVariable String parseJobId, HttpServletRequest request) {
        log.info("[接口访问] GET /users/me/profile/parse-jobs/{parseJobId}, parseJobId={}", parseJobId);
        Long userId = UserContext.getUserId(request);

        Object response = profileService.queryResume(userId, parseJobId);
        log.info("[接口完成] 查询解析任务成功, parseJobId={}, resultType={}",
                parseJobId, response.getClass().getSimpleName());
        return Result.success(response);
    }

    @GetMapping("/users/me/profile")
    public Result<?> getProfile(HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/profile, userId={}, authenticated={}",
                userId, UserContext.isAuthenticated(request));
        Object response = profileService.getProfileResponse(userId);
        log.info("[接口完成] 获取用户最新简历成功, userId={}", userId);
        return Result.success(response);
    }

    @GetMapping("/users/me/profile/list")
    public Result<?> listProfiles(HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/profile/list, userId={}, authenticated={}",
                userId, UserContext.isAuthenticated(request));
        var items = profileService.listProfileItems(userId);
        log.info("[接口完成] 获取简历列表成功, userId={}, count={}", userId, items.size());
        return Result.success(items);
    }

    @GetMapping("/users/me/profile/{profileId}")
    public Result<?> getProfileById(@PathVariable String profileId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/profile/{profileId}, userId={}, profileId={}",
                userId, profileId);
        Object response = profileService.getProfileResponse(userId, profileId);
        log.info("[接口完成] 获取指定简历成功, userId={}, profileId={}", userId, profileId);
        return Result.success(response);
    }

    @DeleteMapping("/users/me/profile/{profileId}")
    public Result<?> deleteProfile(@PathVariable String profileId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] DELETE /users/me/profile/{profileId}, userId={}, profileId={}", userId, profileId);
        int deleted = profileService.deleteProfile(userId, profileId);
        log.info("[接口完成] 删除指定简历成功, userId={}, profileId={}, deleted={}", userId, profileId, deleted);
        return Result.success(Map.of("deleted", deleted));
    }
}
