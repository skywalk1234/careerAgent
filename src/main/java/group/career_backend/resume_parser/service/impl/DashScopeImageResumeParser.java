package group.career_backend.resume_parser.service.impl;

import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversation;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationMessage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationParam;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalConversationResult;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemImage;
import com.alibaba.dashscope.aigc.multimodalconversation.MultiModalMessageItemText;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.JsonUtils;
import group.career_backend.exception.CommonException;
import group.career_backend.resume_parser.service.ImageResumeParser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Base64;
import java.util.List;

@Service
@RequiredArgsConstructor
public class DashScopeImageResumeParser implements ImageResumeParser {
    private static final String MODEL_NAME = "qwen-vl-plus";
    private static final String PROMPT = """
            你是一位专业的简历解析专家。请仔细分析图片中的简历内容，提取基本信息、教育经历、工作经历、项目经历、技能和自我评价，并只返回 JSON。
            无法识别的字段使用空字符串或空数组，不要添加 JSON 之外的说明文字。
            """;

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Override
    public String parse(byte[] imageBytes, String contentType) {
        if (!StringUtils.hasText(apiKey)) {
            throw new CommonException("未配置 DASHSCOPE_API_KEY", 500);
        }

        String mediaType = StringUtils.hasText(contentType) ? contentType : "image/png";
        String imageUrl = "data:" + mediaType + ";base64," + Base64.getEncoder().encodeToString(imageBytes);
        MultiModalConversationMessage systemMessage = MultiModalConversationMessage.builder()
                .role(Role.SYSTEM.getValue())
                .content(List.of(new MultiModalMessageItemText(PROMPT)))
                .build();
        MultiModalConversationMessage userMessage = MultiModalConversationMessage.builder()
                .role(Role.USER.getValue())
                .content(List.of(
                        new MultiModalMessageItemImage(imageUrl),
                        new MultiModalMessageItemText("请解析这份简历图片。")))
                .build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(MODEL_NAME)
                .apiKey(apiKey)
                .message(systemMessage)
                .message(userMessage)
                .vlHighResolutionImages(true)
                .build();

        try {
            return extractContent(new MultiModalConversation().call(param));
        } catch (NoApiKeyException | ApiException | UploadFileException exception) {
            throw new CommonException("图片简历解析失败: " + exception.getMessage(), exception, 500);
        }
    }

    private String extractContent(MultiModalConversationResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null
                || result.getOutput().getChoices().isEmpty()) {
            throw new CommonException("图片简历解析结果为空", 500);
        }

        Object content = result.getOutput().getChoices().get(0).getMessage().getContent();
        if (content instanceof List<?> items && !items.isEmpty()
                && items.get(0) instanceof MultiModalMessageItemText text) {
            return text.getText();
        }
        return JsonUtils.toJson(content);
    }
}
