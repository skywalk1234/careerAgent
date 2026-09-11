package group.career_backend.profile.controller;

import group.career_backend.common.Result;
import group.career_backend.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProfileController {
    private static final long DEFAULT_USER_ID = 111L;

    private final ProfileService profileService;

    @RequestMapping("/users/me/profile/parse-jobs/{parseJobId}")
    public Result<?> queryResume(@PathVariable String parseJobId) {
        log.info("[接口访问] GET /users/me/profile/parse-jobs/{parseJobId}, parseJobId={}", parseJobId);
        Long userId;
        try {
            userId = Long.parseLong(parseJobId);
        } catch (NumberFormatException exception) {
            log.warn("[接口完成] 查询解析任务失败，parseJobId格式错误, parseJobId={}", parseJobId);
            return Result.error(400, "parseJobId格式不正确");
        }

        Object response = profileService.queryResume(userId, parseJobId);
        log.info("[接口完成] 查询解析任务成功, parseJobId={}, resultType={}",
                parseJobId, response.getClass().getSimpleName());
        return Result.success(response);
    }

    @GetMapping("/users/me/profile")
    public Result<?> getProfile(@RequestParam(required = false) String userId) {
        Long resolvedUserId = resolveUserId(userId);
        log.info("[接口访问] GET /users/me/profile, userId={}", resolvedUserId);
        Object response = profileService.getProfileResponse(resolvedUserId);
        log.info("[接口完成] 获取用户最新简历成功, userId={}", resolvedUserId);
        return Result.success(response);
    }

    @GetMapping("/users/me/profile/list")
    public Result<?> listProfiles(@RequestParam(required = false) String userId) {
        Long resolvedUserId = resolveUserId(userId);
        log.info("[接口访问] GET /users/me/profile/list, userId={}", resolvedUserId);
        var items = profileService.listProfileItems(resolvedUserId);
        log.info("[接口完成] 获取简历列表成功, userId={}, count={}", resolvedUserId, items.size());
        return Result.success(items);
    }

    @GetMapping("/users/me/profile/{profileId}")
    public Result<?> getProfileById(@PathVariable String profileId,
                                    @RequestParam(required = false) String userId) {
        Long resolvedUserId = resolveUserId(userId);
        log.info("[接口访问] GET /users/me/profile/{profileId}, userId={}, profileId={}",
                resolvedUserId, profileId);
        Object response = profileService.getProfileResponse(resolvedUserId, profileId);
        log.info("[接口完成] 获取指定简历成功, userId={}, profileId={}", resolvedUserId, profileId);
        return Result.success(response);
    }

    @GetMapping("/users/me/profile/delete")
    public Result<?> deleteProfiles(@RequestParam String userId) {
        log.info("[接口访问] GET /users/me/profile/delete, userId={}", userId);
        int deleted = profileService.deleteProfiles(Long.parseLong(userId));
        log.info("[接口完成] 删除用户简历成功, userId={}, deleted={}", userId, deleted);
        return Result.success();
    }

    private Long resolveUserId(String userId) {
        return userId == null ? DEFAULT_USER_ID : Long.parseLong(userId);
    }

}
