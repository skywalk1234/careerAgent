package group.resumeparserservice.service;/* I love coding */

import lombok.extern.slf4j.Slf4j;
import org.apache.http.client.methods.CloseableHttpResponse;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.entity.StringEntity;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.util.EntityUtils;
import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * DeepSeek V4 Flash 客户端（OpenAI 兼容接口）
 * API Key 在 application.yml 的 deepseek.api-key 中配置
 */
@Component
@Slf4j
public class DeepSeekClient {

    private static final String MODEL = "deepseek-v4-flash";

    @Value("${deepseek.api-key:}")
    private String apiKey;

    @Value("${deepseek.base-url:https://api.deepseek.com}")
    private String baseUrl;

    /**
     * 同步调用 DeepSeek，返回模型生成的文本
     *
     * @param systemPrompt 系统提示词，可为 null
     * @param userContent  用户内容
     */
    public String chat(String systemPrompt, String userContent) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new RuntimeException("未配置 deepseek.api-key，请在 application.yml 中填写（申请地址：https://platform.deepseek.com/）");
        }

        JSONObject body = new JSONObject();
        body.put("model", MODEL);
        body.put("stream", false);
        body.put("temperature", 0.7);

        JSONArray messages = new JSONArray();
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            messages.put(new JSONObject().put("role", "system").put("content", systemPrompt));
        }
        messages.put(new JSONObject().put("role", "user").put("content", userContent));
        body.put("messages", messages);

        String responseBody;
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            HttpPost post = new HttpPost(baseUrl + "/chat/completions");
            post.setHeader("Content-Type", "application/json");
            post.setHeader("Authorization", "Bearer " + apiKey);
            post.setEntity(new StringEntity(body.toString(), "UTF-8"));

            try (CloseableHttpResponse response = client.execute(post)) {
                responseBody = EntityUtils.toString(response.getEntity(), "UTF-8");
                int status = response.getStatusLine().getStatusCode();
                if (status != 200) {
                    log.error("DeepSeek 调用失败, status: {}, body: {}", status, responseBody);
                    throw new RuntimeException("DeepSeek API 调用失败: HTTP " + status);
                }
            }
        } catch (Exception e) {
            log.error("DeepSeek 调用异常", e);
            throw new RuntimeException("DeepSeek API 调用异常: " + e.getMessage(), e);
        }

        JSONObject json = new JSONObject(responseBody);
        String content = json.getJSONArray("choices")
                .getJSONObject(0)
                .getJSONObject("message")
                .getString("content");
        return cleanJsonResponse(content);
    }

    /**
     * 清理大模型返回内容：
     * 1. 去掉 ```json ... ``` Markdown 代码块包裹
     * 2. 提取第一个 { 到最后一个 } 之间的 JSON 主体，容忍前后附加的说明文字
     */
    private String cleanJsonResponse(String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim();
        // 1. 去掉 Markdown 代码块标记
        if (text.startsWith("```")) {
            int firstNewline = text.indexOf('\n');
            text = firstNewline != -1 ? text.substring(firstNewline + 1) : text.replace("```", "");
            if (text.endsWith("```")) {
                text = text.substring(0, text.length() - 3);
            }
            text = text.trim();
        }
        // 2. 提取 JSON 主体
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start != -1 && end > start) {
            text = text.substring(start, end + 1);
        }
        return text;
    }
}
