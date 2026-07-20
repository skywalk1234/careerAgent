package group.resumeparserservice.service;/* I love coding */

import group.resumeparserservice.prompts.AI_comprehend_prompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class AI_comprehend {

    @Autowired
    private ChatClient chatClient;

    public String generateFinalResult(String goal, java.util.List<String> toolResults) {
        log.info("开始生成最终结果，goal: {}", goal);

        group.resumeparserservice.prompts.AI_comprehend_prompt prompt = new group.resumeparserservice.prompts.AI_comprehend_prompt();
        String systemPrompt = AI_comprehend_prompt.buildSystemPrompt(goal, toolResults);

        String aiResponse = chatClient.prompt(systemPrompt)
                .options(ChatOptions.builder().model("qwen-plus-2025-09-11").build())
                .call()
                .content();

        log.info("AI 生成的最终结果长度：{} 字符", aiResponse != null ? aiResponse.length() : 0);

        return aiResponse;
    }
}
