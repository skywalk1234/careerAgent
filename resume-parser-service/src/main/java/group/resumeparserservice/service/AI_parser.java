package group.resumeparserservice.service;/* I love coding */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static group.resumeparserservice.prompts.Prompt_tool.buildExtractionPrompt;

/**
 * 简历解析：调用 DeepSeek V4 Flash 从简历文本中抽取结构化信息
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AI_parser {

    private final DeepSeekClient deepSeekClient;

    public String parse(String resumeText) {
        log.info("开始使用 DeepSeek 解析简历文本，长度: {} 字符", resumeText.length());
        String prompt = buildExtractionPrompt(resumeText);
        return deepSeekClient.chat(null, prompt);
    }
}
