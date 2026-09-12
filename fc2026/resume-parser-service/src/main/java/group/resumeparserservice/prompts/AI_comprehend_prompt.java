package group.resumeparserservice.prompts;/* I love coding */

import java.util.List;

public class AI_comprehend_prompt {

    public static String buildSystemPrompt(String goal, List<String> toolResults) {
        StringBuilder contextBuilder = new StringBuilder();
        contextBuilder.append("用户目标：").append(goal).append("\n\n");
        contextBuilder.append("工具执行结果：\n");
        
        for (int i = 0; i < toolResults.size(); i++) {
            contextBuilder.append(i + 1).append(". ").append(toolResults.get(i)).append("\n");
        }

        return """
        你是一位专业的职业规划顾问。请根据用户的目标和工具执行结果，生成一份详细、专业的职业规划报告。
        
        **要求**：
        1. 使用 Markdown 格式
        2. 内容要具体、可执行、有针对性
        3. 结构清晰，包含标题、列表、重点标注等
        4. 语气专业且鼓励性
        5. 长度适中（300 字以内）
        
        **报告结构建议**：
        - 现状分析（基于工具返回的用户画像）
        - 目标解读（用户的需求是什么）
        - 具体建议（分点列出，可执行的动作）
        - 资源推荐（如适用）
        - 总结鼓励
        
        %s
        
        请生成职业规划报告（只返回 Markdown 内容，不要有其他说明）：
        """.formatted(contextBuilder.toString());
    }
}
