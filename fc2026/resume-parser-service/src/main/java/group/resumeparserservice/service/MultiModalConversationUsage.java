package group.resumeparserservice.service;

import com.alibaba.dashscope.aigc.multimodalconversation.*;
import com.alibaba.dashscope.common.Role;
import com.alibaba.dashscope.exception.ApiException;
import com.alibaba.dashscope.exception.NoApiKeyException;
import com.alibaba.dashscope.exception.UploadFileException;
import com.alibaba.dashscope.utils.JsonUtils;

import java.util.Arrays;

public class MultiModalConversationUsage {

    private static final String modelName = "qwen-vl-plus";

    // 若没有配置环境变量，请用百炼API Key将下行替换为：api_key="sk-xxx"
    public static String apiKey = System.getenv("DASHSCOPE_API_KEY");

    public static void simpleMultiModalConversationCall() throws ApiException, NoApiKeyException, UploadFileException {
        MultiModalConversation conv = new MultiModalConversation();
        MultiModalMessageItemText systemText = new MultiModalMessageItemText("You are a helpful assistant.");
        MultiModalConversationMessage systemMessage = MultiModalConversationMessage.builder()
                .role(Role.SYSTEM.getValue()).content(Arrays.asList(systemText)).build();
        MultiModalMessageItemImage userImage = new MultiModalMessageItemImage(
                "oss://dashscope-instant/9c278a8a1ad615c0bbad6219e5e1eebe/2026-02-22/84f89397-0b87-454c-980f-4dd0bffa6344/resume.png");
        MultiModalMessageItemText userText = new MultiModalMessageItemText("这是什么");
        MultiModalConversationMessage userMessage =
                MultiModalConversationMessage.builder().role(Role.USER.getValue())
                        .content(Arrays.asList(userImage, userText)).build();
        MultiModalConversationParam param = MultiModalConversationParam.builder()
                .model(MultiModalConversationUsage.modelName)
                .apiKey(apiKey)
                .message(systemMessage)
                .vlHighResolutionImages(true)
                .vlEnableImageHwOutput(true)
//                .incrementalOutput(true)
                .message(userMessage).build();
        MultiModalConversationResult result = conv.call(param);
        System.out.print(JsonUtils.toJson(result));

    }

    public static void main(String[] args) {
        try {
            simpleMultiModalConversationCall();
        } catch (ApiException | NoApiKeyException | UploadFileException /*| IOException*/ e) {
            System.out.println(e.getMessage());
        }
        System.exit(0);
    }

}