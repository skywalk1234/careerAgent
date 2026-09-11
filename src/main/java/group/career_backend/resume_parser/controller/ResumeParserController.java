package group.career_backend.resume_parser.controller;

import group.career_backend.common.Result;
import group.career_backend.resume_parser.domain.response.FileParseResponse;
import group.career_backend.resume_parser.service.ResumeParserService;
import group.career_backend.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ResumeParserController {
    private final ResumeParserService resumeParserService;

    @PostMapping("/users/me/profile/parse-jobs")
    public Result<FileParseResponse> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("parseMode") String parseMode,
            HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] POST /users/me/profile/parse-jobs, userId={}, authenticated={}, fileName={}, fileSize={}, parseMode={}",
                userId, UserContext.isAuthenticated(request), file.getOriginalFilename(), file.getSize(), parseMode);
        FileParseResponse response = resumeParserService.submitPdf(file, parseMode, userId);
        log.info("[接口完成] PDF简历解析任务提交成功, parseJobId={}", response.getParseJobId());
        return Result.success(response);
    }

    @PostMapping("/users/me/profile/parse-image")
    public Result<FileParseResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] POST /users/me/profile/parse-image, userId={}, authenticated={}, fileName={}, fileSize={}, contentType={}",
                userId, UserContext.isAuthenticated(request), file.getOriginalFilename(), file.getSize(), file.getContentType());
        FileParseResponse response = resumeParserService.submitImage(file, userId);
        log.info("[接口完成] 图片简历解析任务提交成功, parseJobId={}", response.getParseJobId());
        return Result.success(response);
    }

    @PostMapping("/users/me/profile")
    public Result<Map<String, Object>> saveProfile(
            @RequestBody Map<String, Object> request,
            HttpServletRequest servletRequest) {
        Long userId = UserContext.getUserId(servletRequest);
        String content = request.get("content") instanceof String value ? value : null;
        String profileId = request.get("profileId") instanceof String value ? value : null;
        log.info("[接口访问] POST /users/me/profile, userId={}, authenticated={}, profileId={}, contentLength={}",
                userId, UserContext.isAuthenticated(servletRequest), profileId, content == null ? 0 : content.length());
        Map<String, Object> response = resumeParserService.saveMarkdown(content, profileId, userId);
        log.info("[接口完成] Markdown简历保存任务提交成功, userId={}, profileId={}",
                userId, response.get("profileId"));
        return Result.success(response, "保存成功");
    }
}
