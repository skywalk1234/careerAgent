package group.career_backend.resume_parser.service;

import group.career_backend.resume_parser.domain.response.FileParseResponse;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

public interface ResumeParserService {
    FileParseResponse submitPdf(MultipartFile file, String parseMode, Long userId);

    FileParseResponse submitImage(MultipartFile file, Long userId);

    Map<String, Object> saveMarkdown(String content, String profileId, Long userId);
}
