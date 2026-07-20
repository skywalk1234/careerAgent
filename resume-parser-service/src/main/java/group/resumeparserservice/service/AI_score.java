package group.resumeparserservice.service;/* I love coding */

import group.resumeparserservice.constant.Test_json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.stereotype.Service;

import static group.resumeparserservice.prompts.Prompt_tool.buildScorePrompt;

@Service
@RequiredArgsConstructor
@Slf4j
public class AI_score {
    private final ChatClient chatClient;

    public String resume_score(String resumeText){
        log.info("开始使用AI评分简历文本，长度: {} 字符", resumeText.length());
        try {
            // 1. 构建详细的prompt，为了节省token，先用测试的json来搞
            String prompt = buildScorePrompt(resumeText);
//            String res_json = "";
            String res_json = chatClient
                    .prompt(prompt) // 传入user提示词
                    .call() // 同步请求，会等待AI全部输出完才返回结果
                    .content(); //返回响应内容


//            System.out.println("提取结果："+res_json);
            res_json = Test_json.score_json();
            return res_json;

        } catch (Exception e) {
            log.error("AI评分失败", e);
            throw new RuntimeException("评分失败: " + e.getMessage(), e);
        }

    }

}
