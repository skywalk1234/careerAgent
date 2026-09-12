package group.resumeparserservice.service;/* I love coding */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.el.lang.ExpressionBuilder;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

@Service
@Slf4j
public class AI_route_planning {
    private final ChatClient chatClient;
    private final VectorStore vectorStore;
    private final ObjectMapper objectMapper;

    public AI_route_planning(ChatClient chatClient,
                             @Qualifier("jobDetailVectorStore") VectorStore vectorStore,
                             ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }

    // 定义元数据键常量，避免硬编码错误
    private static final String META_JOB_NAME = "jobName";
    private static final String META_JOB_ID = "jobId";
    private static final String META_COMPANY = "companyName";
    private static final String META_SALARY = "salaryMin";
    private static final String META_SKILLS = "score_professionalSkill";

    // 日期格式化
    private static final DateTimeFormatter DTO_FORMATTER = DateTimeFormatter.ISO_OFFSET_DATE_TIME;

//    职业路径规划
public String routePlanning(List<String> jobCategories, String userProfile) {
    log.info("开始使用AI进行职业路径规划，目标类别: {}, 用户画像长度: {}", jobCategories.size(), userProfile.length());

    if (jobCategories == null || jobCategories.isEmpty()) {
        throw new IllegalArgumentException("岗位类别列表不能为空");
    }

    // 1. 检索相关岗位：每个类别取 Top 3
    List<String> allJobDetailsJson = new ArrayList<>();

    for (String category : jobCategories) {
        log.debug("正在检索类别: {}", category);

        try {
            FilterExpressionBuilder builder = new FilterExpressionBuilder();
            Filter.Expression filterExpression = builder.eq(META_JOB_NAME, category).build();
            SearchRequest searchRequest = SearchRequest.builder()
                    .query(userProfile)             // 设置查询向量/文本
                    .topK(3)                        // 设置返回数量
                    .filterExpression(filterExpression) // 设置过滤条件
                    .similarityThreshold(0.3)       // 设置相似度阈值
                    .build();                       // 【关键】必须调用 build() 生成对象

            // 执行搜索
            List<Document> results = vectorStore.similaritySearch(searchRequest);

            if (results.isEmpty()) {
                log.warn("类别 [{}] 未找到匹配岗位，跳过。", category);
                continue;
            }

            // 提取岗位信息并转换为精简的 JSON 字符串供大模型消费
            for (Document doc : results) {
                String jobInfo = extractJobInfoForPrompt(doc);
                allJobDetailsJson.add(jobInfo);
            }

        } catch (Exception e) {
            log.error("检索类别 [{}] 时发生异常", category, e);
            // 继续处理其他类别，不中断整体流程
        }
    }

    if (allJobDetailsJson.isEmpty()) {
        return buildErrorResponse("未找到匹配的岗位信息，请尝试调整岗位名称或完善个人画像。");
    }

    // 2. 构建 Prompt 并调用大模型
    return generatePathWithLLM(allJobDetailsJson, userProfile);
}

    /**
     * 从 Document 中提取关键信息，序列化为 JSON 字符串片段
     */
    private String extractJobInfoForPrompt(Document doc) {
        Map<String, Object> metadata = doc.getMetadata();

        // 使用 ObjectMapper 构建 JSON 节点
        ObjectNode node = objectMapper.createObjectNode();

        // 1. 基础标识信息
        node.put("jobId", getStringOrNull(metadata, "jobId"));
        node.put("jobName", getStringOrNull(metadata, "jobName"));
        node.put("jobCode", getStringOrNull(metadata, "jobCode"));
        node.put("level", getStringOrNull(metadata, "level")); // e.g., "mid", "junior"

        // 2. 公司与薪资信息
        node.put("companyName", getStringOrNull(metadata, "companyName"));
        node.put("companySize", getStringOrNull(metadata, "companySize"));

        // 处理薪资 (注意元数据中是 Double 类型: 3.0, 4.0)
        Double salaryMin = getDoubleOrNull(metadata, "salaryMin");
        Double salaryMax = getDoubleOrNull(metadata, "salaryMax");
        String salaryUnit = getStringOrNull(metadata, "salaryUnit"); // e.g., "k/月"

        if (salaryMin != null && salaryMax != null) {
            node.put("salaryRange", salaryMin + "-" + salaryMax + salaryUnit);
        } else {
            node.put("salaryRange", "面议");
        }

        node.put("city", getStringOrNull(metadata, "city"));

        // 3. 核心技能描述 (最关键的部分，来自 dimensionDetails.professionalSkill)
        // 需要嵌套读取 dimensionDetails 对象
        String professionalSkillDesc = "";
        if (metadata.containsKey("dimensionDetails")) {
            Object dimObj = metadata.get("dimensionDetails");
            if (dimObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> dimDetails = (Map<String, Object>) dimObj;
                professionalSkillDesc = getStringOrNull(dimDetails, "professionalSkill");
            }
        }
        node.put("coreSkillDescription", professionalSkillDesc);

        // 4. 关键能力评分 (提取几个核心维度，避免 Token 浪费)
        // 提取：专业技能、学习能力、实习经验、领导力、沟通力
        node.put("score_professionalSkill", getDoubleOrNull(metadata, "score_professionalSkill"));
        node.put("score_learning", getDoubleOrNull(metadata, "score_learning"));
        node.put("score_internship", getDoubleOrNull(metadata, "score_internship"));
        node.put("score_leadership", getDoubleOrNull(metadata, "score_leadership"));
        node.put("score_communication", getDoubleOrNull(metadata, "score_communication"));
        node.put("score_execution", getDoubleOrNull(metadata, "score_execution"));

        // 5. 原始职位描述 (Content)，这是向量匹配的核心依据，包含详细JD
        // 注意：doc.getContent() 返回的是构建向量时的文本，通常包含完整的 JD
        node.put("jobDescription", (String)metadata.get("jobDescription"));
        node.put("companyDescription", (String)metadata.get("companyDescription"));

        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            log.error("序列化岗位信息失败", e);
            return "{}";
        }
    }

    // --- 辅助方法：安全获取类型数据 ---

    private String getStringOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        return val != null ? val.toString() : "";
    }

    private Double getDoubleOrNull(Map<String, Object> map, String key) {
        Object val = map.get(key);
        if (val == null) return null;
        if (val instanceof Number) {
            return ((Number) val).doubleValue();
        }
        try {
            return Double.parseDouble(val.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 调用大模型生成路径
     */
    private String generatePathWithLLM(List<String> jobDetailsJsonList, String userProfile) {
        String jobsContext = String.join(",\n", jobDetailsJsonList);
        String currentTime = ZonedDateTime.now().format(DTO_FORMATTER);

        String systemPrompt = """
        你是一名资深的大学生职业规划专家。你的任务是根据提供的【候选岗位池】和【用户画像】，规划一条清晰的、直线型的职业发展路径。

        ### 核心约束
        1. **路径形态**：必须是直线型（Start -> Milestone -> Target），严禁分叉。
        2. **节点数量**：严格控制在 3 到 4 个节点之间。
        3. **数据来源**：节点必须源自【候选岗位池】，若是基于池子推导的进阶岗位，必须标记 `"inferred": true`。
        4. **输出格式**：**必须且只能**返回一个合法的 JSON 对象。**严禁**包含 markdown 标记（如 ```json ... ```）、注释或任何额外文本。
        5. **时间格式**：`createdAt` 和 `updatedAt` 这两个字段不用管，原样返回即可

        ### JSON 结构定义 (必须严格遵守)
        你必须按照以下 JSON 模板填充数据，字段名称、类型、嵌套结构不可更改：

        {
          "pathId": "string (生成唯一ID，如 cp_10001)",
          "pathName": "string (简短有力的路径名称，如 '前端到AI应用路径')",
          "pathNodes": [
            {
              "id": "string (节点唯一ID，如 pn_1)",
              "jobId": "string (对应候选池中的jobId，若是推导则生成新ID)",
              "jobName": "string (岗位名称)",
              "stage": "string (枚举值: 'start', 'milestone', 'target' 或其他自定义阶段名)"
            }
            // 必须有 3-4 个这样的对象
          ],
          "pathEdges": [
            {
              "source": "string (对应前一个节点的 id)",
              "target": "string (对应后一个节点的 id)",
              "relationType": "string (固定为 'transition')",
              "similarity": "number (0.0-1.0 之间的浮点数)",
              "difficulty": "string (枚举: 'low', 'medium', 'high')",
              "reason": "string (简述过渡需要补齐的能力或缺口)",
              "inferred": "boolean (若目标节点是推导出来的则为 true)"
            }
            // 数量应为 节点数-1
          ],
          "evaluation": {
            "level": "string (如 'deep', 'medium')",
            "feasibilityScore": "integer (0-100)",
            "readinessScore": "integer (0-100)",
            "recommendationScore": "integer (0-100)",
            "riskAlerts": ["string (风险点列表，若无则为空数组)"],
            "aiCommentary": "string (专家点评，100字以内)",
            "stagePlans": [
              {
                "stage": "string (阶段标识)",
                "stageLabel": "string (阶段中文标签)",
                "cycle": "string (时间周期，如 '0-3个月')",
                "goals": ["string (目标列表)"],
                "suggestedTasks": [
                  {
                    "title": "string (任务标题)",
                    "linkText": "string (可选，网站名称，若无则省略此字段)",
                    "linkUrl": "string (可选，网址，若无则省略此字段)"
                  }
                ],
                "metrics": ["string (考核指标列表)"]
              }
              // 至少包含 1 个阶段计划
            ],
            "summaryMetrics": [
              { "key": "string", "label": "string", "value": "number" }
              // 必须包含 avgSimilarity, nodeCount, targetMatch 这三个 key
            ]
          },
          "createdAt": "2026-3-20",
          "updatedAt": "2026-3-20"
        }

        ### 输入数据
        【候选岗位池】:
        %s

        【用户画像】:
        %s

        ### 执行步骤
        1. 分析用户画像与候选岗位的匹配度，确定起点 (Start)。
        2. 基于技能依赖关系，规划中间节点 (Milestone) 和终点 (Target)。
        3. 计算边 (Edges) 的相似度和难度，撰写过渡理由。
        4. 制定具体的行动计划 (stagePlans)，确保任务可落地。
        5. **直接输出**填充好数据的完整 JSON 字符串，不要有任何多余的前缀或后缀。
        """.formatted(jobsContext, userProfile);

        try {
            log.info("大模型生成路径开始，当前时间：{}", currentTime);
            String response = chatClient.prompt()
                    .options(ChatOptions.builder().model("qwen-plus-2025-09-11").build())
                    .user(systemPrompt)
                    .call()
                    .content();

            // 简单的清理，防止大模型返回 markdown 标记
            if (response.startsWith("```json")) {
                response = response.substring(7);
            }
            if (response.endsWith("```")) {
                response = response.substring(0, response.length() - 3);
            }

            // 验证 JSON 合法性 (可选，生产环境建议加上)
            objectMapper.readTree(response);

            return response.trim();

        } catch (Exception e) {
            log.error("大模型生成路径失败", e);
            return buildErrorResponse("路径规划生成失败：" + e.getMessage());
        }
    }

    /**
     * 构建错误响应的 JSON 结构，保持格式一致
     */
    private String buildErrorResponse(String message) {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("pathId", "error_" + UUID.randomUUID().toString().substring(0, 8));
        root.put("pathName", "规划失败");
        root.putArray("pathNodes");
        root.putArray("pathEdges");

        ObjectNode eval = objectMapper.createObjectNode();
        eval.put("level", "error");
        eval.put("feasibilityScore", 0);
        eval.put("aiCommentary", message);
        eval.putArray("stagePlans");
        eval.putArray("summaryMetrics");

        root.set("evaluation", eval);
        root.put("createdAt", ZonedDateTime.now().format(DTO_FORMATTER));
        root.put("updatedAt", ZonedDateTime.now().format(DTO_FORMATTER));

        try {
            return objectMapper.writeValueAsString(root);
        } catch (JsonProcessingException e) {
            return "{\"error\": \"JSON serialization failed\"}";
        }
    }
}
