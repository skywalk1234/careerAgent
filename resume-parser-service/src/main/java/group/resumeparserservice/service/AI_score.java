package group.resumeparserservice.service;/* I love coding */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static group.resumeparserservice.prompts.Prompt_tool.buildScorePrompt;

/**
 * 简历评分：调用 DeepSeek V4 Flash 按 12 个维度评估简历
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AI_score {

    private final DeepSeekClient deepSeekClient;

    public String resume_score(String resumeText) {
        log.info("开始使用 DeepSeek 评分简历，长度: {} 字符", resumeText.length());
        String prompt = buildScorePrompt(resumeText);
        return deepSeekClient.chat(null, prompt);
    }
}
