package group.resumeparserservice.prompts;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class JobAnalyzePrompt {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    public static String getPrompt(String requestJson) {
        try {
            JsonNode rootNode = objectMapper.readTree(requestJson);
            
            String studentProfile = "";
            String jobProfile = "";
            
            if (rootNode.has("studentProfile")) {
                studentProfile = rootNode.get("studentProfile").toString();
            }
            
            if (rootNode.has("jobProfile")) {
                jobProfile = rootNode.get("jobProfile").toString();
            }
            
            return buildPrompt(studentProfile, jobProfile);
        } catch (Exception e) {
            log.error("解析人岗分析请求参数失败", e);
            return buildPrompt(requestJson, "");
        }
    }

    private static String buildPrompt(String studentProfile, String jobProfile) {
        StringBuilder prompt = new StringBuilder();
        
        prompt.append("你是一位专业的人岗匹配分析专家。你的任务是根据用户画像和岗位画像，进行多维度的详细匹配分析。\n\n");
        
        prompt.append("### 输入数据\n");
        prompt.append("**用户画像**：\n");
        prompt.append(studentProfile).append("\n\n");
        prompt.append("**岗位画像**：\n");
        prompt.append(jobProfile).append("\n\n");
        
        prompt.append("### 分析任务\n");
        prompt.append("请基于上述用户画像和岗位画像，进行以下多维度分析：\n\n");
        
        prompt.append("1. **基础要求维度 (basicRequirement)**\n");
        prompt.append("   - 评估学历、专业、工作年限等硬性条件匹配度\n");
        prompt.append("   - 分析城市、薪资期望等软性条件契合度\n\n");
        
        prompt.append("2. **职业技能维度 (professionalSkill)**\n");
        prompt.append("   - 对比用户技能栈与岗位技能要求的匹配程度\n");
        prompt.append("   - 评估技术深度和广度是否满足岗位需求\n\n");
        
        prompt.append("3. **职业素养维度 (professionalLiteracy)**\n");
        prompt.append("   - 分析沟通能力、团队协作、抗压能力等软技能\n");
        prompt.append("   - 评估职业态度、责任心等职业素养匹配度\n\n");
        
        prompt.append("4. **发展潜力维度 (developmentPotential)**\n");
        prompt.append("   - 评估学习能力、成长潜力与岗位发展路径的匹配\n");
        prompt.append("   - 分析创新思维、适应能力等长期发展指标\n\n");
        
        prompt.append("### 评分规则\n");
        prompt.append("- 各维度得分范围：0-100分\n");
        prompt.append("- 岗位期望得分 (jobExpectedScores)：该岗位在该维度的基准要求分数\n");
        prompt.append("- 学生能力得分 (studentAbilityScores)：学生在各细分能力项的得分\n");
        prompt.append("- 岗位能力得分 (jobAbilityScores)：岗位对各细分能力项的要求分数\n");
        prompt.append("- 综合得分 (overallScore)：加权计算的总匹配度分数\n\n");
        
        prompt.append("### 细分能力项说明\n");
        prompt.append("- professionalSkill: 专业技能\n");
        prompt.append("- certificate: 证书资质\n");
        prompt.append("- innovation: 创新能力\n");
        prompt.append("- internalMotivation: 内驱动力\n");
        prompt.append("- learning: 学习能力\n");
        prompt.append("- stressTolerance: 抗压能力\n");
        prompt.append("- communication: 沟通能力\n");
        prompt.append("- internship: 实习经历\n");
        prompt.append("- language: 语言能力\n");
        prompt.append("- leadership: 领导力\n");
        prompt.append("- adaptability: 适应能力\n");
        prompt.append("- execution: 执行力\n\n");
        
        prompt.append("### 输出规范\n");
        prompt.append("1. **格式要求**：必须输出一个标准的JSON对象，不能包含任何```json```代码块标记，不能包含任何解释性文字。\n");
        prompt.append("2. **字段约束**：JSON的Key必须与下方Schema完全一致。\n");
        prompt.append("3. **数据要求**：所有分数必须是0-100之间的整数，分析理由必须具体、有针对性。\n\n");
        
        prompt.append("### 严格JSON Schema\n");
        prompt.append("请严格按照以下结构输出：\n\n");
        
        prompt.append("{\n");
        prompt.append("  \"job\": {\n");
        prompt.append("    \"jobId\": \"岗位ID\",\n");
        prompt.append("    \"jobName\": \"岗位名称\",\n");
        prompt.append("    \"companyName\": \"公司名称\",\n");
        prompt.append("    \"city\": \"城市\",\n");
        prompt.append("    \"industryTags\": [\"行业标签1\", \"行业标签2\"],\n");
        prompt.append("    \"educationRequirement\": \"学历要求\",\n");
        prompt.append("    \"salaryNegotiable\": false,\n");
        prompt.append("    \"salaryNormalized\": \"薪资范围\",\n");
        prompt.append("    \"updatedAtRaw\": \"更新时间\",\n");
        prompt.append("    \"level\": \"岗位级别\"\n");
        prompt.append("  },\n");
        prompt.append("  \"analysis\": {\n");
        prompt.append("    \"overallScore\": 84,\n");
        prompt.append("    \"dimensionScores\": {\n");
        prompt.append("      \"basicRequirement\": 86,\n");
        prompt.append("      \"professionalSkill\": 82,\n");
        prompt.append("      \"professionalLiteracy\": 80,\n");
        prompt.append("      \"developmentPotential\": 83\n");
        prompt.append("    },\n");
        prompt.append("    \"jobExpectedScores\": {\n");
        prompt.append("      \"basicRequirement\": 78,\n");
        prompt.append("      \"professionalSkill\": 75,\n");
        prompt.append("      \"professionalLiteracy\": 68,\n");
        prompt.append("      \"developmentPotential\": 61\n");
        prompt.append("    },\n");
        prompt.append("    \"studentAbilityScores\": {\n");
        prompt.append("      \"professionalSkill\": 78,\n");
        prompt.append("      \"certificate\": 62,\n");
        prompt.append("      \"innovation\": 70,\n");
        prompt.append("      \"internalMotivation\": 74,\n");
        prompt.append("      \"learning\": 88,\n");
        prompt.append("      \"stressTolerance\": 72,\n");
        prompt.append("      \"communication\": 80,\n");
        prompt.append("      \"internship\": 74,\n");
        prompt.append("      \"language\": 67,\n");
        prompt.append("      \"leadership\": 61,\n");
        prompt.append("      \"adaptability\": 76,\n");
        prompt.append("      \"execution\": 73\n");
        prompt.append("    },\n");
        prompt.append("    \"jobAbilityScores\": {\n");
        prompt.append("      \"professionalSkill\": 84,\n");
        prompt.append("      \"certificate\": 72,\n");
        prompt.append("      \"innovation\": 75,\n");
        prompt.append("      \"internalMotivation\": 70,\n");
        prompt.append("      \"learning\": 82,\n");
        prompt.append("      \"stressTolerance\": 68,\n");
        prompt.append("      \"communication\": 78,\n");
        prompt.append("      \"internship\": 80,\n");
        prompt.append("      \"language\": 70,\n");
        prompt.append("      \"leadership\": 65,\n");
        prompt.append("      \"adaptability\": 74,\n");
        prompt.append("      \"execution\": 79\n");
        prompt.append("    },\n");
        prompt.append("    \"dimensionAnalysis\": {\n");
        prompt.append("      \"basicRequirement\": {\n");
        prompt.append("        \"label\": \"基础要求\",\n");
        prompt.append("        \"score\": 86,\n");
        prompt.append("        \"expectedScore\": 78,\n");
        prompt.append("        \"reason\": \"分析理由，需具体说明匹配情况\",\n");
        prompt.append("        \"confidence\": \"high/medium/low\"\n");
        prompt.append("      },\n");
        prompt.append("      \"professionalSkill\": {\n");
        prompt.append("        \"label\": \"职业技能\",\n");
        prompt.append("        \"score\": 82,\n");
        prompt.append("        \"expectedScore\": 77,\n");
        prompt.append("        \"reason\": \"分析理由\",\n");
        prompt.append("        \"confidence\": \"medium\"\n");
        prompt.append("      },\n");
        prompt.append("      \"professionalLiteracy\": {\n");
        prompt.append("        \"label\": \"职业素养\",\n");
        prompt.append("        \"score\": 80,\n");
        prompt.append("        \"expectedScore\": 73,\n");
        prompt.append("        \"reason\": \"分析理由\",\n");
        prompt.append("        \"confidence\": \"medium\"\n");
        prompt.append("      },\n");
        prompt.append("      \"developmentPotential\": {\n");
        prompt.append("        \"label\": \"发展潜力\",\n");
        prompt.append("        \"score\": 83,\n");
        prompt.append("        \"expectedScore\": 69,\n");
        prompt.append("        \"reason\": \"分析理由\",\n");
        prompt.append("        \"confidence\": \"medium\"\n");
        prompt.append("      }\n");
        prompt.append("    },\n");
        prompt.append("    \"matchTags\": [\n");
        prompt.append("      \"高匹配\",\n");
        prompt.append("      \"优势维度：职业技能\"\n");
        prompt.append("    ],\n");
        prompt.append("    \"improvementSuggestions\": [\n");
        prompt.append("      {\n");
        prompt.append("        \"dimension\": \"certificate\",\n");
        prompt.append("        \"priority\": \"high/medium/low\",\n");
        prompt.append("        \"advice\": \"具体的提升建议\"\n");
        prompt.append("      }\n");
        prompt.append("    ]\n");
        prompt.append("  }\n");
        prompt.append("}");
        
        return prompt.toString();
    }
}
