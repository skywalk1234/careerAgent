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
        Long userId;
        try {
            userId = Long.parseLong(parseJobId);
        } catch (NumberFormatException exception) {
            return Result.error(400, "parseJobId格式不正确");
        }

        return Result.success(profileService.queryResume(userId, parseJobId));
    }

    @GetMapping("/users/me/profile")
    public Result<?> getProfile(@RequestParam(required = false) String userId) {
        return Result.success(profileService.getProfileResponse(resolveUserId(userId)));
    }

    @GetMapping("/users/me/profile/list")
    public Result<?> listProfiles(@RequestParam(required = false) String userId) {
        return Result.success(profileService.listProfileItems(resolveUserId(userId)));
    }

    @GetMapping("/users/me/profile/{profileId}")
    public Result<?> getProfileById(@PathVariable String profileId,
                                    @RequestParam(required = false) String userId) {
        return Result.success(profileService.getProfileResponse(resolveUserId(userId), profileId));
    }

    @GetMapping("/users/me/profile/delete")
    public Result<?> deleteProfiles(@RequestParam String userId) {
        int deleted = profileService.deleteProfiles(Long.parseLong(userId));
        log.info("删除用户 {} 的 {} 份简历", userId, deleted);
        return Result.success();
    }

    private Long resolveUserId(String userId) {
        return userId == null ? DEFAULT_USER_ID : Long.parseLong(userId);
    }

}
