package group.resumeparserservice.service;/* I love coding */

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import static group.resumeparserservice.prompts.Prompt_tool.buildMarkdownPrompt;

/**
 * 简历整理：调用 DeepSeek 将简历原始文本整理为可读性更强的 Markdown 格式
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AI_parser {

    private final DeepSeekClient deepSeekClient;

    public String parse(String resumeText) {
        log.info("开始使用 DeepSeek 整理简历 Markdown，长度: {} 字符", resumeText.length());
        String prompt = buildMarkdownPrompt(resumeText);
        return deepSeekClient.chat(null, prompt);
    }
}
