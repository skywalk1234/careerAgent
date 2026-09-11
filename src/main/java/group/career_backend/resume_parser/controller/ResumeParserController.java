package group.career_backend.resume_parser.controller;

import group.career_backend.common.Result;
import group.career_backend.resume_parser.domain.response.FileParseResponse;
import group.career_backend.resume_parser.service.ResumeParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
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
            @RequestHeader(value = "user-info", required = false) Long userId) {
        log.info("[接口访问] POST /users/me/profile/parse-jobs, userId={}, fileName={}, fileSize={}, parseMode={}",
                userId, file.getOriginalFilename(), file.getSize(), parseMode);
        FileParseResponse response = resumeParserService.submitPdf(file, parseMode, userId);
        log.info("[接口完成] PDF简历解析任务提交成功, parseJobId={}", response.getParseJobId());
        return Result.success(response);
    }

    @PostMapping("/users/me/profile/parse-image")
    public Result<FileParseResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "user-info", required = false) Long userId) {
        log.info("[接口访问] POST /users/me/profile/parse-image, userId={}, fileName={}, fileSize={}, contentType={}",
                userId, file.getOriginalFilename(), file.getSize(), file.getContentType());
        FileParseResponse response = resumeParserService.submitImage(file, userId);
        log.info("[接口完成] 图片简历解析任务提交成功, parseJobId={}", response.getParseJobId());
        return Result.success(response);
    }

    @PostMapping("/users/me/profile")
    public Result<Map<String, Object>> saveProfile(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "user-info", required = false) Long userId) {
        String content = request.get("content") instanceof String value ? value : null;
        String profileId = request.get("profileId") instanceof String value ? value : null;
        log.info("[接口访问] POST /users/me/profile, userId={}, profileId={}, contentLength={}",
                userId, profileId, content == null ? 0 : content.length());
        Map<String, Object> response = resumeParserService.saveMarkdown(content, profileId, userId);
        log.info("[接口完成] Markdown简历保存任务提交成功, userId={}, profileId={}",
                userId, response.get("profileId"));
        return Result.success(response, "保存成功");
    }
}
