package group.career_backend.resume_parser.controller;

import group.career_backend.common.Result;
import group.career_backend.resume_parser.domain.response.FileParseResponse;
import group.career_backend.resume_parser.service.ResumeParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

@RestController
@RequiredArgsConstructor
public class ResumeParserController {
    private final ResumeParserService resumeParserService;

    @PostMapping("/users/me/profile/parse-jobs")
    public Result<FileParseResponse> uploadPdf(
            @RequestParam("file") MultipartFile file,
            @RequestParam("parseMode") String parseMode,
            @RequestHeader(value = "user-info", required = false) Long userId) {
        return Result.success(resumeParserService.submitPdf(file, parseMode, userId));
    }

    @PostMapping("/users/me/profile/parse-image")
    public Result<FileParseResponse> uploadImage(
            @RequestParam("file") MultipartFile file,
            @RequestHeader(value = "user-info", required = false) Long userId) {
        return Result.success(resumeParserService.submitImage(file, userId));
    }

    @PostMapping("/users/me/profile")
    public Result<Map<String, Object>> saveProfile(
            @RequestBody Map<String, Object> request,
            @RequestHeader(value = "user-info", required = false) Long userId) {
        String content = request.get("content") instanceof String value ? value : null;
        String profileId = request.get("profileId") instanceof String value ? value : null;
        return Result.success(resumeParserService.saveMarkdown(content, profileId, userId), "保存成功");
    }
}
