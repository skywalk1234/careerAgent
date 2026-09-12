package group.resumeparserservice.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RoadMappingPolishPrompt {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String getPrompt(String requestJson) {
        try {
            JsonNode rootNode = objectMapper.readTree(requestJson);
            String tone = rootNode.has("tone") ? rootNode.get("tone").asText("professional") : "professional";
            String targetReader = rootNode.has("targetReader") ? rootNode.get("targetReader").asText("校招面试官") : "校招面试官";
            
            StringBuilder focusInstructions = new StringBuilder();
            if (rootNode.has("focusSections")) {
                JsonNode focusSections = rootNode.get("focusSections");
                
                if (focusSections.has("pathStrategy")) {
                    JsonNode pathStrategy = focusSections.get("pathStrategy");
                    if (pathStrategy.has("instruction")) {
                        focusInstructions.append("\n### 路径策略(pathStrategy)润色要求\n");
                        focusInstructions.append("- ").append(pathStrategy.get("instruction").asText()).append("\n");
                    }
                }
                
                if (focusSections.has("stagePlan")) {
                    JsonNode stagePlan = focusSections.get("stagePlan");
                    if (stagePlan.has("instruction")) {
                        focusInstructions.append("\n### 阶段计划(stagePlan)润色要求\n");
                        focusInstructions.append("- ").append(stagePlan.get("instruction").asText()).append("\n");
                    }
                }
            }

            String originalReport = "";
            if (rootNode.has("originalReport")) {
                originalReport = rootNode.get("originalReport").toString();
            }

            return buildPrompt(tone, targetReader, focusInstructions.toString(), originalReport);
        } catch (Exception e) {
            log.error("解析润色请求参数失败", e);
            return buildPrompt("professional", "校招面试官", "", requestJson);
        }
    }

    private static String buildPrompt(String tone, String targetReader, String focusInstructions, String originalReport) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("你是一位专业的职业生涯规划报告润色专家。你的任务是根据用户的润色要求，对原始生涯报告进行优化和改写。\n\n");
        
        prompt.append("### 润色要求\n");
        prompt.append("1. **语气风格(tone)**: ").append(getToneDescription(tone)).append("\n");
        prompt.append("2. **目标读者(targetReader)**: ").append(targetReader).append("\n");
        
        if (!focusInstructions.isEmpty()) {
            prompt.append(focusInstructions);
        }
        
        prompt.append("\n### 原始生涯报告\n");
        prompt.append(originalReport).append("\n\n");
        
        prompt.append("### 润色任务说明\n");
        prompt.append("请基于上述原始报告和润色要求，生成一份润色后的生涯报告。你需要：\n");
        prompt.append("1. 保持原始报告的结构完整性（8个核心模块）\n");
        prompt.append("2. 根据指定的语气风格调整表达方式\n");
        prompt.append("3. 针对目标读者优化内容呈现\n");
        prompt.append("4. 特别关注用户指定的重点润色章节\n");
        prompt.append("5. 确保所有内容基于原始报告的事实，不编造不存在的数据\n\n");
        
        prompt.append("### 输出规范\n");
        prompt.append("1. **格式要求**：必须输出一个标准的JSON对象，不能包含任何```json```代码块标记，不能包含任何解释性文字。\n");
        prompt.append("2. **字段约束**：JSON的Key必须与下方示例完全一致。\n");
        prompt.append("3. **内容要求**：润色后的内容应更加专业、具体、有说服力，同时保持数据真实性。\n\n");
        
        prompt.append("### 严格JSON Schema\n");
        prompt.append("请严格按照以下结构输出，注意嵌套层级和字段名称：\n\n");
        
        prompt.append("{\n");
        prompt.append("  \"executiveSummary\": {\n");
        prompt.append("    \"title\": \"执行摘要\",\n");
        prompt.append("    \"content\": \"润色后的总结性文字...\"\n");
        prompt.append("  },\n");
        prompt.append("  \"currentAssessment\": {\n");
        prompt.append("    \"title\": \"现状评估\",\n");
        prompt.append("    \"content\": \"润色后的现状描述...\"\n");
        prompt.append("  },\n");
        prompt.append("  \"targetAnalysis\": {\n");
        prompt.append("    \"title\": \"目标岗位分析\",\n");
        prompt.append("    \"content\": \"润色后的目标分析...\"\n");
        prompt.append("  },\n");
        prompt.append("  \"pathStrategy\": {\n");
        prompt.append("    \"title\": \"路径策略\",\n");
        prompt.append("    \"content\": \"润色后的路径策略...\"\n");
        prompt.append("  },\n");
        prompt.append("  \"stagePlan\": {\n");
        prompt.append("    \"title\": \"阶段计划\",\n");
        prompt.append("    \"milestones\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"stageLabel\": \"阶段名称\",\n");
        prompt.append("        \"cycle\": \"时间周期\",\n");
        prompt.append("        \"goals\": [\"目标1\", \"目标2\"],\n");
        prompt.append("        \"tasks\": [\"任务1\", \"任务2\"],\n");
        prompt.append("        \"deliverables\": [\"交付物1\", \"交付物2\"]\n");
        prompt.append("      }\n");
        prompt.append("    ]\n");
        prompt.append("  },\n");
        prompt.append("  \"riskControl\": {\n");
        prompt.append("    \"title\": \"风险与对策\",\n");
        prompt.append("    \"items\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"risk\": \"风险描述\",\n");
        prompt.append("        \"impact\": \"风险影响\",\n");
        prompt.append("        \"mitigation\": \"缓解措施\",\n");
        prompt.append("        \"owner\": \"责任人\"\n");
        prompt.append("      }\n");
        prompt.append("    ]\n");
        prompt.append("  },\n");
        prompt.append("  \"resourceRecommendations\": {\n");
        prompt.append("    \"title\": \"资源建议\",\n");
        prompt.append("    \"courses\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"name\": \"课程名称\",\n");
        prompt.append("        \"provider\": \"提供方\",\n");
        prompt.append("        \"url\": \"链接\"\n");
        prompt.append("      }\n");
        prompt.append("    ],\n");
        prompt.append("    \"communities\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"name\": \"社区名称\",\n");
        prompt.append("        \"url\": \"链接\"\n");
        prompt.append("      }\n");
        prompt.append("    ],\n");
        prompt.append("    \"certifications\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"name\": \"证书名称\",\n");
        prompt.append("        \"reason\": \"推荐理由\"\n");
        prompt.append("      }\n");
        prompt.append("    ]\n");
        prompt.append("  },\n");
        prompt.append("  \"reviewMechanism\": {\n");
        prompt.append("    \"title\": \"复盘机制\",\n");
        prompt.append("    \"cadence\": \"复盘频率\",\n");
        prompt.append("    \"checkpoints\": [\"检查点1\", \"检查点2\"],\n");
        prompt.append("    \"adjustmentRule\": \"调整规则\"\n");
        prompt.append("  }\n");
        prompt.append("}");
        
        return prompt.toString();
    }
    
    private static String getToneDescription(String tone) {
        switch (tone.toLowerCase()) {
            case "professional":
                return "专业正式 - 使用规范的职场语言，适合正式场合展示";
            case "friendly":
                return "友好亲切 - 语言温暖易懂，适合个人阅读";
            case "concise":
                return "简洁明了 - 直击要点，去除冗余描述";
            case "detailed":
                return "详细全面 - 充分展开论述，提供深度分析";
            default:
                return "专业正式";
        }
    }
}
