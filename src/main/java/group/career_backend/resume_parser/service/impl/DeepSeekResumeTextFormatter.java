package group.career_backend.resume_parser.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.career_backend.exception.CommonException;
import group.career_backend.resume_parser.service.ResumeTextFormatter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
public class DeepSeekResumeTextFormatter implements ResumeTextFormatter {
    private static final String MARKDOWN_INSTRUCTION = """
            请将以下简历的原始文本整理成结构清晰、可读性强的 Markdown 格式。

            要求：
            1. 使用合适的 Markdown 语法，包括标题、列表、加粗和换行
            2. 合理划分基本信息、教育背景、工作或实习经历、项目经历、技能、证书、获奖情况、自我评价等区域
            3. 保留原始文本中的所有内容和细节，不得增删、改写或概括事实信息
            4. 只返回整理后的 Markdown 文本，不要包含解释文字，不要使用代码块包裹

            以下是需要整理的简历原始文本：

            """;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    @Value("${deepseek.model:deepseek-v4-flash}")
    private String model;

    @Override
    public String formatAsMarkdown(String resumeText) {
        if (!StringUtils.hasText(apiKey)) {
            throw new CommonException("未配置 DEEPSEEK_API_KEY", 500);
        }
        if (!StringUtils.hasText(resumeText)) {
            throw new CommonException("简历文本内容为空", 500);
        }

        log.info("[业务处理] 开始调用DeepSeek整理简历, model={}, textLength={}", model, resumeText.length());
        try {
            Map<String, Object> body = Map.of(
                    "model", model,
                    "stream", false,
                    "temperature", 0.2,
                    "messages", List.of(Map.of(
                            "role", "user",
                            "content", MARKDOWN_INSTRUCTION + resumeText)));
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + "/chat/completions"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .header("Authorization", "Bearer " + apiKey)
                    .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("[业务处理] DeepSeek调用失败, status={}", response.statusCode());
                throw new CommonException("DeepSeek API调用失败: HTTP " + response.statusCode(), 500);
            }

            JsonNode content = objectMapper.readTree(response.body())
                    .path("choices").path(0).path("message").path("content");
            if (!content.isTextual() || !StringUtils.hasText(content.asText())) {
                throw new CommonException("DeepSeek返回的简历内容为空", 500);
            }
            String markdown = stripCodeFence(content.asText());
            log.info("[业务处理] DeepSeek简历整理完成, model={}, markdownLength={}", model, markdown.length());
            return markdown;
        } catch (CommonException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommonException("DeepSeek调用被中断", exception, 500);
        } catch (Exception exception) {
            log.error("[业务处理] DeepSeek调用异常, model={}", model, exception);
            throw new CommonException("DeepSeek API调用异常: " + exception.getMessage(), exception, 500);
        }
    }

    private String normalizeBaseUrl(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String stripCodeFence(String value) {
        String text = value.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        if (firstLineEnd >= 0) {
            text = text.substring(firstLineEnd + 1);
        }
        if (text.endsWith("```")) {
            text = text.substring(0, text.length() - 3);
        }
        return text.trim();
    }
}
