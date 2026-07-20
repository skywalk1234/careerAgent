package group.resumeparserservice.service;/* I love coding */

import com.alibaba.dashscope.aigc.multimodalconversation.*;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.JsonUtils;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Arrays;
import java.util.Base64;

@Slf4j
@Service
public class AI_image_parser {

    private static final String MODEL_NAME = "qwen-vl-plus";

    @Value("${dashscope.api-key:${DASHSCOPE_API_KEY:}}")
    private String apiKey;

    /**
     * 解析图片中的简历信息
     *
     * @param imageBytes 图片字节数组
     * @param fileName   文件名
     * @return 解析结果JSON字符串
     */
    public String parseResumeImage(byte[] imageBytes, String fileName) {
        log.info("开始解析图片简历，文件名: {}", fileName);

        try {
            // 将图片转为Base64
            String base64Image = Base64.getEncoder().encodeToString(imageBytes);
            String imageUrl = "data:image/png;base64," + base64Image;

            MultiModalConversation conv = new MultiModalConversation();

            // 系统提示词
            MultiModalMessageItemText systemText = new MultiModalMessageItemText(
                "你是一位专业的简历解析专家。请仔细分析图片中的简历内容，提取以下信息并以JSON格式返回：\n" +
                "{\n" +
                "  \"basicInfo\": {\n" +
                "    \"name\": \"姓名\",\n" +
                "    \"phone\": \"电话\",\n" +
                "    \"email\": \"邮箱\",\n" +
                "    \"gender\": \"性别\",\n" +
                "    \"age\": \"年龄\",\n" +
                "    \"location\": \"所在地\"\n" +
                "  },\n" +
                "  \"education\": [\n" +
                "    {\n" +
                "      \"school\": \"学校名称\",\n" +
                "      \"major\": \"专业\",\n" +
                "      \"degree\": \"学历\",\n" +
                "      \"startDate\": \"开始时间\",\n" +
                "      \"endDate\": \"结束时间\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"workExperience\": [\n" +
                "    {\n" +
                "      \"company\": \"公司名称\",\n" +
                "      \"position\": \"职位\",\n" +
                "      \"startDate\": \"开始时间\",\n" +
                "      \"endDate\": \"结束时间\",\n" +
                "      \"description\": \"工作描述\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"projectExperience\": [\n" +
                "    {\n" +
                "      \"name\": \"项目名称\",\n" +
                "      \"role\": \"担任角色\",\n" +
                "      \"description\": \"项目描述\"\n" +
                "    }\n" +
                "  ],\n" +
                "  \"skills\": [\"技能1\", \"技能2\"],\n" +
                "  \"selfEvaluation\": \"自我评价\"\n" +
                "}\n" +
                "如果某些字段在图片中无法识别，请使用空字符串或空数组。只返回JSON，不要添加其他说明文字。"
            );
            MultiModalConversationMessage systemMessage = MultiModalConversationMessage.builder()
                    .role(Role.SYSTEM.getValue())
                    .content(Arrays.asList(systemText))
                    .build();

            // 用户图片消息
            MultiModalMessageItemImage userImage = new MultiModalMessageItemImage(imageUrl);
            MultiModalMessageItemText userText = new MultiModalMessageItemText("请解析这份简历图片，提取关键信息并返回JSON格式数据。");
            MultiModalConversationMessage userMessage = MultiModalConversationMessage.builder()
                    .role(Role.USER.getValue())
                    .content(Arrays.asList(userImage, userText))
                    .build();

            // 构建请求参数
            MultiModalConversationParam param = MultiModalConversationParam.builder()
                    .model(MODEL_NAME)
                    .apiKey(apiKey)
                    .message(systemMessage)
                    .message(userMessage)
                    .vlHighResolutionImages(true)
                    .build();

            // 调用API
            MultiModalConversationResult result = conv.call(param);

            // 提取结果
            String content = extractContent(result);
            log.info("图片简历解析完成，文件名: {}", fileName);
            return content;

        } catch (NoApiKeyException e) {
            log.error("DashScope API Key 未配置");
            return "{\"error\": \"API Key未配置\"}";
        } catch (ApiException | UploadFileException e) {
            log.error("图片解析失败: {}", e.getMessage(), e);
            return "{\"error\": \"" + e.getMessage() + "\"}";
        }
    }

    /**
     * 从API结果中提取内容
     */
    private String extractContent(MultiModalConversationResult result) {
        if (result == null || result.getOutput() == null || result.getOutput().getChoices() == null) {
            return "{\"error\": \"解析结果为空\"}";
        }

        try {
            // 获取第一个choice的内容
            Object content = result.getOutput().getChoices().get(0).getMessage().getContent();
            if (content instanceof java.util.List) {
                java.util.List<?> contentList = (java.util.List<?>) content;
                if (!contentList.isEmpty()) {
                    Object firstItem = contentList.get(0);
                    if (firstItem instanceof MultiModalMessageItemText) {
                        return ((MultiModalMessageItemText) firstItem).getText();
                    }
                }
            }
            return JsonUtils.toJson(content);
        } catch (Exception e) {
            log.error("提取内容失败: {}", e.getMessage());
            return "{\"error\": \"提取内容失败\"}";
        }
    }
}
