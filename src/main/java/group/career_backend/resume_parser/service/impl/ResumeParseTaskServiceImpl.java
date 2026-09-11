package group.career_backend.resume_parser.service.impl;

import group.career_backend.exception.CommonException;
import group.career_backend.resume_parser.domain.dto.ResumeParseMessage;
import group.career_backend.resume_parser.service.ResumeParseTaskService;
import group.career_backend.resume_parser.service.ResumeTextFormatter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class ResumeParseTaskServiceImpl implements ResumeParseTaskService {
    private static final String PROFILE_QUEUE = "profile_storage";

    private final ResumeTextFormatter resumeTextFormatter;
    private final RabbitTemplate rabbitTemplate;

    @Override
    public void parse(ResumeParseMessage message) {
        validate(message);
        log.info("[业务处理] 开始处理简历解析任务, userId={}, fileName={}, parseMode={}",
                message.getUserId(), message.getFileName(), message.getParseMode());

        String rawText = "image".equalsIgnoreCase(message.getParseMode())
                ? new String(message.getFileContent(), StandardCharsets.UTF_8)
                : extractPdfText(message.getFileContent());
        log.info("[业务处理] 简历文本提取完成, userId={}, fileName={}, textLength={}",
                message.getUserId(), message.getFileName(), rawText.length());

        String markdown = resumeTextFormatter.formatAsMarkdown(rawText);
        Map<String, Object> profileMessage = new HashMap<>();
        profileMessage.put("userId", message.getUserId());
        profileMessage.put("profileData", markdown);
        profileMessage.put("fileName", message.getFileName());
        profileMessage.put("fileType", message.getFileType());
        profileMessage.put("timestamp", System.currentTimeMillis());

        log.info("[MQ消息] 准备发送解析结果到 profile_storage, userId={}, fileName={}",
                message.getUserId(), message.getFileName());
        rabbitTemplate.convertAndSend(PROFILE_QUEUE, profileMessage);
        log.info("[业务处理] 简历解析任务处理完成, userId={}, fileName={}",
                message.getUserId(), message.getFileName());
    }

    private String extractPdfText(byte[] content) {
        log.info("[业务处理] 开始提取PDF文本, fileSize={}", content.length);
        try (PDDocument document = Loader.loadPDF(content)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            stripper.setWordSeparator(" ");
            String text = stripper.getText(document);
            if (!StringUtils.hasText(text)) {
                throw new CommonException("PDF中未提取到文本", 500);
            }
            log.info("[业务处理] PDF文本提取完成, pages={}, textLength={}",
                    document.getNumberOfPages(), text.length());
            return text;
        } catch (IOException exception) {
            throw new CommonException("PDF文件解析失败: " + exception.getMessage(), exception, 500);
        }
    }

    private void validate(ResumeParseMessage message) {
        if (message == null || message.getUserId() == null) {
            throw new CommonException("解析任务缺少userId", 500);
        }
        if (message.getFileContent() == null || message.getFileContent().length == 0) {
            throw new CommonException("解析任务文件内容为空", 500);
        }
    }
}
