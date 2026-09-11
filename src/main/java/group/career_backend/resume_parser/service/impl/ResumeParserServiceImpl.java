package group.career_backend.resume_parser.service.impl;

import group.career_backend.exception.CommonException;
import group.career_backend.resume_parser.domain.dto.ResumeParseMessage;
import group.career_backend.resume_parser.domain.response.FileParseResponse;
import group.career_backend.resume_parser.service.ImageResumeParser;
import group.career_backend.resume_parser.service.ResumeParserService;
import group.career_backend.security.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
@Slf4j
public class ResumeParserServiceImpl implements ResumeParserService {
    private static final String FILE_QUEUE = "file_tran";
    private static final String PROFILE_QUEUE = "profile_storage";
    private static final int POLL_AFTER_MS = 2000;
    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/jpeg", "image/jpg", "image/png", "image/gif", "image/bmp", "image/webp");

    private final RabbitTemplate rabbitTemplate;
    private final ImageResumeParser imageResumeParser;

    @Override
    public FileParseResponse submitPdf(MultipartFile file, String parseMode, Long userId) {
        log.info("[业务处理] 开始处理PDF简历上传, userId={}, fileName={}, parseMode={}",
                userId, file == null ? null : file.getOriginalFilename(), parseMode);
        requireFile(file);
        Long resolvedUserId = userId == null ? UserContext.DEFAULT_USER_ID : userId;
        log.info("[业务处理] PDF文件校验通过，开始读取文件, userId={}, fileSize={}",
                resolvedUserId, file.getSize());
        ResumeParseMessage message = new ResumeParseMessage(
                resolvedUserId,
                file.getOriginalFilename(),
                getBytes(file),
                file.getContentType(),
                parseMode);
        sendParseMessage(message);
        log.info("[业务处理] PDF简历解析任务已提交, userId={}", resolvedUserId);
        return processingResponse(resolvedUserId);
    }

    @Override
    public FileParseResponse submitImage(MultipartFile file, Long userId) {
        log.info("[业务处理] 开始处理图片简历上传, userId={}, fileName={}",
                userId, file == null ? null : file.getOriginalFilename());
        requireFile(file);
        if (!IMAGE_TYPES.contains(file.getContentType())) {
            log.warn("[业务处理] 图片格式校验失败, contentType={}", file.getContentType());
            throw new CommonException("不支持的文件类型，请上传图片文件(jpg, jpeg, png, gif, bmp, webp)", 400);
        }

        Long resolvedUserId = userId == null ? UserContext.DEFAULT_USER_ID : userId;
        log.info("[业务处理] 图片格式校验通过，开始调用图片识别服务, userId={}, contentType={}",
                resolvedUserId, file.getContentType());
        String parsedContent = imageResumeParser.parse(getBytes(file), file.getContentType());
        log.info("[业务处理] 图片识别完成，开始提交后续解析任务, userId={}, resultLength={}",
                resolvedUserId, parsedContent.length());
        ResumeParseMessage message = new ResumeParseMessage(
                resolvedUserId,
                file.getOriginalFilename(),
                parsedContent.getBytes(StandardCharsets.UTF_8),
                "application/json",
                "image");
        sendParseMessage(message);
        log.info("[业务处理] 图片简历解析任务已提交, userId={}", resolvedUserId);
        return processingResponse(resolvedUserId);
    }

    @Override
    public Map<String, Object> saveMarkdown(String content, String profileId, Long userId) {
        log.info("[业务处理] 开始提交Markdown简历保存任务, userId={}, profileId={}, contentLength={}",
                userId, profileId, content == null ? 0 : content.length());
        if (!StringUtils.hasText(content)) {
            log.warn("[业务处理] Markdown简历内容为空, userId={}, profileId={}", userId, profileId);
            throw new CommonException("content字段不能为空", 114);
        }

        Long resolvedUserId = userId == null ? UserContext.DEFAULT_USER_ID : userId;
        Map<String, Object> message = new HashMap<>();
        message.put("userId", resolvedUserId.toString());
        message.put("profileId", profileId);
        message.put("profileData", content);
        message.put("fileName", null);
        message.put("fileType", null);
        message.put("timestamp", System.currentTimeMillis());
        log.info("[MQ消息] 准备发送 profile_storage 消息, userId={}, profileId={}",
                resolvedUserId, profileId);
        rabbitTemplate.convertAndSend(PROFILE_QUEUE, message);
        log.info("[MQ消息] profile_storage 消息发送完成, userId={}, profileId={}",
                resolvedUserId, profileId);

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
        log.info("[MQ消息] 准备发送 file_tran 消息, userId={}, fileName={}, parseMode={}",
                message.getUserId(), message.getFileName(), message.getParseMode());
        rabbitTemplate.convertAndSend(FILE_QUEUE, message);
        log.info("[MQ消息] file_tran 消息发送完成, userId={}, fileName={}",
                message.getUserId(), message.getFileName());
    }

    private FileParseResponse processingResponse(Long userId) {
        return new FileParseResponse(userId.toString(), "processing", POLL_AFTER_MS);
    }

    private void requireFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            log.warn("[业务处理] 上传文件为空");
            throw new CommonException("文件不能为空", 400);
        }
    }

    private byte[] getBytes(MultipartFile file) {
        try {
            return file.getBytes();
        } catch (IOException exception) {
            log.error("[业务处理] 文件读取失败, fileName={}", file.getOriginalFilename(), exception);
            throw new CommonException("文件读取失败", exception, 500);
        }
    }
}
