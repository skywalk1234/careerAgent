package group.career_backend.job_function.job_analyze.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.career_backend.exception.CommonException;
import group.career_backend.job_function.job_analyze.domain.dto.AiAnalyzeRequest;
import group.career_backend.job_function.job_analyze.domain.dto.AiRecommendRequest;
import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobResult;
import group.career_backend.job_function.job_analyze.domain.dto.MatchJob;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Component
@Slf4j
public class JobAnalyzeAiClient {
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();

    @Value("${ai-service.url:http://127.0.0.1:8086}")
    private String baseUrl;

    @Value("${ai-service.job-analyze-path:/job/analyze}")
    private String analyzePath;

    public MatchJob recommend(AiRecommendRequest body) {
        return post("/jobs/recommend/specific", body, MatchJob.class, "岗位推荐");
    }

    public AnalyzeJobResult analyze(AiAnalyzeRequest body) {
        return post(analyzePath, body, AnalyzeJobResult.class, "岗位分析");
    }

    private <T> T post(String path, Object body, Class<T> responseType, String operation) {
        try {
            String requestBody = objectMapper.writeValueAsString(body);
            log.info("[业务处理] 开始调用AI{}, path={}", operation, path);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(normalizeBaseUrl(baseUrl) + normalizePath(path)))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                log.error("[业务处理] AI{}调用失败, status={}, body={}", operation, response.statusCode(), response.body());
                throw new CommonException("AI" + operation + "调用失败: HTTP " + response.statusCode(), 502);
            }
            T result = parseResponse(response.body(), responseType);
            log.info("[业务处理] AI{}调用完成", operation);
            return result;
        } catch (CommonException exception) {
            throw exception;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new CommonException("AI" + operation + "调用被中断", exception, 500);
        } catch (Exception exception) {
            log.error("[业务处理] AI{}调用异常", operation, exception);
            throw new CommonException("AI" + operation + "调用异常: " + exception.getMessage(), exception, 502);
        }
    }

    private <T> T parseResponse(String body, Class<T> responseType) throws Exception {
        String json = stripCodeFence(body);
        JsonNode root = objectMapper.readTree(json);
        if (root.isTextual()) {
            root = objectMapper.readTree(stripCodeFence(root.asText()));
        }
        if (root.hasNonNull("error")) {
            throw new CommonException("AI服务返回错误: " + root.get("error").asText(), 502);
        }
        if (root.has("data") && root.get("data").isObject()) {
            root = root.get("data");
        }
        return objectMapper.treeToValue(root, responseType);
    }

    private String normalizeBaseUrl(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private String normalizePath(String value) {
        return value.startsWith("/") ? value : "/" + value;
    }

    private String stripCodeFence(String value) {
        String text = value == null ? "" : value.trim();
        if (!text.startsWith("```")) {
            return text;
        }
        int firstLineEnd = text.indexOf('\n');
        int lastFence = text.lastIndexOf("```");
        return firstLineEnd >= 0 && lastFence > firstLineEnd
                ? text.substring(firstLineEnd + 1, lastFence).trim() : text;
    }
}
