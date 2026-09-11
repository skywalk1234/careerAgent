package group.career_backend.profile.controller;

import group.career_backend.common.Result;
import group.career_backend.profile.domain.dto.StudentProfile;
import group.career_backend.profile.domain.po.ResumeFull;
import group.career_backend.profile.domain.response.QueryResumeResponse;
import group.career_backend.profile.domain.response.ResumeProcessingResponse;
import group.career_backend.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProfileController {
    private static final long DEFAULT_USER_ID = 111L;

    private final ProfileService profileService;

    @RabbitListener(queues = "profile_storage")
    public void storeProfile(Map<String, Object> message) {
        Long userId = toLong(message.get("userId"));
        if (userId == null) {
            throw new IllegalArgumentException("profile_storage message is missing userId");
        }

        StudentProfile profile = new StudentProfile();
        profile.setId(userId);
        profile.setContent((String) message.get("profileData"));
        profileService.saveProfile(
                profile,
                userId,
                (String) message.get("profileId"),
                (String) message.get("fileName"),
                (String) message.get("fileType"));
        log.info("用户 {} 的简历已保存", userId);
    }

    @RequestMapping("/users/me/profile/parse-jobs/{parseJobId}")
    public Result<?> queryResume(@PathVariable String parseJobId) {
        Long userId;
        try {
            userId = Long.parseLong(parseJobId);
        } catch (NumberFormatException exception) {
            return Result.error(400, "parseJobId格式不正确");
        }

        ResumeFull resume = profileService.getLatestResume(userId);
        if (resume == null) {
            return Result.success(new ResumeProcessingResponse(parseJobId));
        }

        StudentProfile profile = resume.getResumeData();
        List<String> missingFields = new ArrayList<>();
        if (profile == null || profile.getContent() == null || profile.getContent().trim().isEmpty()) {
            missingFields.add("content");
        }
        Map<String, String> sourceMeta = new HashMap<>();
        sourceMeta.put("fileName", resume.getFileName());
        sourceMeta.put("fileType", resume.getFileType());

        QueryResumeResponse response = new QueryResumeResponse();
        response.setParseJobId(parseJobId);
        response.setStatus("success");
        response.setResult(new QueryResumeResponse.ParseResult(profile, missingFields, sourceMeta));
        return Result.success(response);
    }

    @GetMapping("/users/me/profile")
    public Result<?> getProfile(@RequestParam(required = false) String userId) {
        return Result.success(profileService.getProfileResponse(resolveUserId(userId)));
    }

    @GetMapping("/users/me/profile/list")
    public Result<?> listProfiles(@RequestParam(required = false) String userId) {
        List<Map<String, Object>> items = profileService.listResumes(resolveUserId(userId)).stream()
                .map(this::toListItem)
                .toList();
        return Result.success(items);
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

    private Long toLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }

    private Map<String, Object> toListItem(ResumeFull resume) {
        Map<String, Object> item = new HashMap<>();
        StudentProfile profile = resume.getResumeData();
        String content = profile == null ? null : profile.getContent();
        item.put("profileId", resume.getProfileId());
        item.put("content", content);
        item.put("title", buildResumeTitle(content));
        item.put("updatedAt", resume.getUpdatedAt() == null ? null : resume.getUpdatedAt().toString());
        item.put("fileName", resume.getFileName());
        item.put("fileType", resume.getFileType());
        return item;
    }

    private String buildResumeTitle(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "未命名简历";
        }
        String firstLine = content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        String title = firstLine.replaceFirst("^[#>*_\\-\\s]+", "");
        if (title.isEmpty()) {
            title = firstLine;
        }
        return title.length() > 20 ? title.substring(0, 20) + "…" : title;
    }
}
