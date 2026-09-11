package group.career_backend.resume_parser.service.impl;

import group.career_backend.exception.CommonException;
import group.career_backend.resume_parser.domain.dto.ResumeParseMessage;
import group.career_backend.resume_parser.domain.response.FileParseResponse;
import group.career_backend.resume_parser.service.ImageResumeParser;
import group.career_backend.resume_parser.service.ResumeParserService;
import lombok.RequiredArgsConstructor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ResumeParserServiceImpl implements ResumeParserService {
    private static final String FILE_QUEUE = "file_tran";
    private static final String PROFILE_QUEUE = "profile_storage";
    private static final String LEGACY_MESSAGE_TYPE =
            "group.resumeparserservice.domain.dto.ResumeParseMessage";
    private static final long DEFAULT_PARSE_USER_ID = 23L;
    private static final long DEFAULT_PROFILE_USER_ID = 111L;
    private static final int POLL_AFTER_MS = 2000;
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/bmp", "image/webp");

    private final RabbitTemplate rabbitTemplate;
    private final ImageResumeParser imageResumeParser;

    @Override
    public FileParseResponse submitPdf(MultipartFile file, String parseMode, Long userId) {
        requireFile(file);
        Long resolvedUserId = userId == null ? DEFAULT_PARSE_USER_ID : userId;
        ResumeParseMessage message = new ResumeParseMessage(
                resolvedUserId,
                file.getOriginalFilename(),
                getBytes(file),
                file.getContentType(),
                parseMode);
        sendParseMessage(message);
        return processingResponse(resolvedUserId);
    }

    @Override
    public FileParseResponse submitImage(MultipartFile file, Long userId) {
        requireFile(file);
        if (!IMAGE_TYPES.contains(file.getContentType())) {
            throw new CommonException("不支持的文件类型，请上传图片文件(jpg, jpeg, png, gif, bmp, webp)", 400);
        }

        Long resolvedUserId = userId == null ? DEFAULT_PARSE_USER_ID : userId;
        String parsedContent = imageResumeParser.parse(getBytes(file), file.getContentType());
        ResumeParseMessage message = new ResumeParseMessage(
                resolvedUserId,
                file.getOriginalFilename(),
                parsedContent.getBytes(StandardCharsets.UTF_8),
                "application/json",
                "image");
        sendParseMessage(message);
        return processingResponse(resolvedUserId);
    }

    @Override
    public Map<String, Object> saveMarkdown(String content, String profileId, Long userId) {
        if (!StringUtils.hasText(content)) {
            throw new CommonException("content字段不能为空", 114);
        }

        Long resolvedUserId = userId == null ? DEFAULT_PROFILE_USER_ID : userId;
        Map<String, Object> message = new HashMap<>();
        message.put("userId", resolvedUserId.toString());
        message.put("profileId", profileId);
        message.put("profileData", content);
        message.put("fileName", null);
        message.put("fileType", null);
        message.put("timestamp", System.currentTimeMillis());
        rabbitTemplate.convertAndSend(PROFILE_QUEUE, message);

        Map<String, Object> response = new HashMap<>();
        response.put("profileId", StringUtils.hasText(profileId) ? profileId : resolvedUserId.toString());
        response.put("updatedAt", OffsetDateTime.now().toString());
        response.put("analysisStatus", "succeeded");
        response.put("scores", null);
        response.put("evidence", null);
        response.put("improvementSuggestions", null);
        return response;
    }

    private void sendParseMessage(ResumeParseMessage message) {
        MessagePostProcessor legacyTypeHeader = this::useLegacyMessageType;
        rabbitTemplate.convertAndSend(FILE_QUEUE, message, legacyTypeHeader);
    }

    private Message useLegacyMessageType(Message message) {
        message.getMessageProperties().setHeader("__TypeId__", LEGACY_MESSAGE_TYPE);
        return message;
    }

    private FileParseResponse processingResponse(Long userId) {
        return new FileParseResponse(userId.toString(), "processing", POLL_AFTER_MS);
    }

    private void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new CommonException("文件不能为空", 400);
        }
    }

    private byte[] getBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            throw new CommonException("文件读取失败", exception, 500);
        }
    }
}
