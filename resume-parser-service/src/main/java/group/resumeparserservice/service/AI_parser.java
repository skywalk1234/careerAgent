package group.resumeparserservice.service;/* I love coding */

import group.resumeparserservice.constant.Test_json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.stereotype.Service;

import static group.resumeparserservice.prompts.Prompt_tool.buildExtractionPrompt;

@Service
@RequiredArgsConstructor
@Slf4j
public class AI_parser {

    private final ChatClient chatClient;

    public String parse(String resumeText) {
        // 模拟调用AI接口进行解析
        log.info("开始使用AI解析简历文本，长度: {} 字符", resumeText.length());

        try {
            String prompt = buildExtractionPrompt(resumeText);
//                String res_json = "";
            String res_json = chatClient
                    .prompt(prompt) // 传入user提示词
                    .options(
                            ChatOptions.builder()
                                    .model("qwen3-0.6b")  // 动态设置模型
                                    .build()
                    )
                    .call() // 同步请求，会等待AI全部输出完才返回结果
                    .content(); //返回响应内容


//            System.out.println("提取结果："+res_json);
//            res_json = Test_json.test_json();
            return res_json;

        } catch (Exception e) {
            log.error("AI解析简历失败", e);
            throw new RuntimeException("简历解析失败: " + e.getMessage(), e);
        }
//        return null;
    }
}
