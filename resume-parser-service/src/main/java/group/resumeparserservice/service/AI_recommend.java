package group.resumeparserservice.service;/* I love coding */

import com.alibaba.nacos.client.naming.utils.CollectionUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@Slf4j
public class AI_recommend {
    private final RabbitTemplate rabbitTemplate;
    private final ChatClient chatClient;
    private final VectorStore vectorStore;          // 字段不需要 @Qualifier
    private final VectorStore jobDetailVectorStore; // 字段不需要 @Qualifier
    private final ObjectMapper objectMapper;

    // 【关键】必须在构造函数参数上加 @Qualifier("beanName")
    public AI_recommend(
            RabbitTemplate rabbitTemplate,
            ChatClient chatClient,
            @Qualifier("pgVectorVectorStore") VectorStore vectorStore,
            @Qualifier("jobDetailVectorStore") VectorStore jobDetailVectorStore,
            ObjectMapper objectMapper
    ) {
        this.rabbitTemplate = rabbitTemplate;
        this.chatClient = chatClient;
        this.vectorStore = vectorStore;             // 赋值给字段
        this.jobDetailVectorStore = jobDetailVectorStore; // 赋值给字段
        this.objectMapper = objectMapper;
    }



    // 构造函数注入


    /**
     * 核心推荐方法
     * @param json 用户输入的 JSON 字符串 (包含技能、经历、意向等)
     * @return 仅包含岗位名称列表的 JSON 字符串，例如: ["岗位A", "岗位B"]
     */
    public String recommendCategory(String json) {
        log.info("开始基于向量检索进行岗位推荐...");
//        log.info("用户画像 JSON: {}", json);

        try {
            // 1. 直接使用用户输入的 JSON 字符串作为查询语料
            // 向量模型会自动理解这段文本的语义，去匹配库里的岗位描述
            // 不需要额外提取字段，整段 JSON 包含的信息量最大
            List<Document> results = vectorStore.similaritySearch(
                    SearchRequest.builder().query(json).topK(5).similarityThreshold(0.3).build()
                     // 设置一个较低的阈值，确保能召回
            );

            if (CollectionUtils.isEmpty(results)) {
                log.warn("未检索到任何匹配岗位");
                return "[]";
            }

            // 2. 直接从检索结果的元数据中提取 nodeName
            List<String> jobNames = results.stream()
                    .map(doc -> {
                        Map<String, Object> metadata = doc.getMetadata();
                        return (String) metadata.get("nodeName");
                    })
                    .filter(name -> name != null) // 过滤掉可能为 null 的情况
                    .collect(Collectors.toList());

            log.info("检索到的岗位列表: {}", jobNames);

            // 3. 直接用 Jackson 将 List 转为 JSON 字符串
            // 根本不需要调用大模型来生成这个列表，本地代码生成更快更准
            return objectMapper.writeValueAsString(jobNames);

        } catch (Exception e) {
            log.error("推荐过程出错", e);
            return "[]";
        }
    }

    public String recommendSpecificJob(String userJson) {
        log.info("开始基于向量检索与大模型比对的岗位推荐流程...");

        try {
            // =======================
            // 第一步：向量检索 (Retrieve)
            // =======================
            // 直接使用用户输入的 JSON 字符串作为查询语料。
            // 向量模型会理解其中的求职意愿、技能和目标岗位语义。
            List<Document> searchResults = jobDetailVectorStore.similaritySearch(
                    SearchRequest.builder()
                            .query(userJson)
                            .topK(5) // 召回前 5 个最相关的岗位
                            .similarityThreshold(0.2) // 设置较低阈值，确保有结果供大模型筛选
                            .build()
            );

            if (CollectionUtils.isEmpty(searchResults)) {
                log.warn("向量检索未返回任何结果");
                return "{\"error\": \"未找到匹配的岗位信息\"}";
            }

            log.info("向量检索完成，召回 {} 个候选岗位", searchResults.size());

            // =======================
            // 第二步：构建大模型上下文 (Context Building)
            // =======================

            // 1. 提取检索到的岗位详细信息列表
            List<Map<String, Object>> candidateJobs = new ArrayList<>();
            for (Document doc : searchResults) {
                Map<String, Object> metadata = doc.getMetadata();
                // 确保只传递需要的字段给大模型，减少 Token 消耗，同时保持结构清晰
                Map<String, Object> jobInfo = new HashMap<>();

                // 基础信息
                jobInfo.put("jobId", metadata.get("jobId"));
                jobInfo.put("jobName", metadata.get("jobName"));
                jobInfo.put("companyName", metadata.get("companyName"));
                jobInfo.put("city", metadata.get("city"));
                jobInfo.put("district", metadata.get("district"));
                jobInfo.put("level", metadata.get("level"));
                jobInfo.put("educationRequirement", metadata.get("educationRequirement"));
                jobInfo.put("salaryNormalized", metadata.get("salaryNormalized"));
                jobInfo.put("salaryMin", metadata.get("salaryMin"));
                jobInfo.put("salaryMax", metadata.get("salaryMax"));
                jobInfo.put("updatedAtRaw", metadata.get("updatedAtRaw"));

                // 能力评分 (用于大模型参考)
//                if (metadata.containsKey("score_professionalSkill")) {
//                    jobInfo.put("score_professionalSkill", metadata.get("score_professionalSkill"));
//                }
//                if (metadata.containsKey("score_communication")) {
//                    jobInfo.put("score_communication", metadata.get("score_communication"));
//                }
                // ... 可以按需添加其他分数


                candidateJobs.add(jobInfo);
            }

            // 2. 构建 Prompt
            String systemPrompt = """
                你是一位资深的招聘专家和职业顾问。你的任务是根据【用户画像】和【候选岗位列表】，进行深度的匹配分析。
                
                【任务要求】
                1. 仔细分析用户的求职意愿、技能栈、经验水平和薪资期望。
                2. 逐一评估【候选岗位列表】中的每个职位与用户的匹配度。
                3. 选出 **1 个** 最佳匹配岗位 (bestMatch) 和 **最多 4 个** 其他推荐岗位 (otherRecommendations)。
                4. 为每个推荐岗位生成一个 0-100 的综合匹配分 (overallScore)。
                5. 为最佳匹配岗位生成详细的维度评分 (basicRequirement, professionalSkill, professionalLiteracy, developmentPotential)。
                6. 生成简练的匹配标签 (matchTags)，如 "高匹配", "薪资略低但发展好", "技术栈重合" 等。
                
                【输出约束】
                - 必须严格且仅返回标准的 JSON 格式，不要包含任何 Markdown 标记（如 ```json ... ```），不要包含任何解释性文字。
                - 返回的 JSON 结构必须完全符合以下 Schema：
                  {
                    "bestMatch": {
                      "jobId": "string",
                      "jobName": "string",
                      "companyName": "string",
                      "city": "string",
                      "educationRequirement": "string",
                      "salaryNegotiable": boolean,
                      "salaryNormalized": "string",
                      "updatedAtRaw": "string",
                      "level": "string",
                      "overallScore": number,
                      "matchTags": ["string"],
                      "dimensionScores": {
                        "basicRequirement": number,
                        "professionalSkill": number,
                        "professionalLiteracy": number,
                        "developmentPotential": number
                      }
                    },
                    "otherRecommendations": [
                      {
                        "jobId": "string",
                        "jobName": "string",
                        "companyName": "string",
                        "city": "string",
                        "educationRequirement": "string",
                        "salaryNegotiable": boolean,
                        "salaryNormalized": "string",
                        "updatedAtRaw": "string",
                        "level": "string",
                        "overallScore": number,
                        "matchTags": ["string"]
                      }
                    ]
                  }
                """;

            String userPrompt = """
                【用户画像与意愿】
                %s
                
                【候选岗位列表 (来自向量检索)】
                %s
                
                请根据上述信息进行匹配分析，并返回严格的 JSON 结果。
                """.formatted(userJson, objectMapper.writeValueAsString(candidateJobs));

            // =======================
            // 第三步：调用大模型 (Generate)
            // =======================

            // 假设你注入了 ChatClient 或 ChatModel (Spring AI 标准接口)
            // 如果没有注入，请使用你项目中现有的调用方式替换此处
            String content = chatClient.prompt()
                    .options(ChatOptions.builder().model("qwen-plus-2025-09-11").build())
                    .system(systemPrompt)
                    .user(userPrompt)
                    .call()
                    .content();

//            String content = response.getResult().getOutput().getText();

            // 清理可能存在的 Markdown 标记 (防止模型不听话加了 ```json)
            content = content.replaceAll("```json", "").replaceAll("```", "").trim();

            // 验证 JSON 合法性 (可选，但推荐)
            try {
                JsonNode jsonNode = objectMapper.readTree(content);
                if (!jsonNode.has("bestMatch")) {
                    log.error("大模型返回的 JSON 缺少 bestMatch 字段: {}", content);
                    // 这里可以选择抛出异常或返回错误提示
                    return "{\"error\": \"模型返回格式不正确\"}";
                }
            } catch (JsonProcessingException e) {
                log.error("大模型返回的内容不是合法的 JSON: {}", content, e);
                return "{\"error\": \"模型返回解析失败\"}";
            }

            log.info("推荐完成，返回结果长度: {}", content.length());
            return content;

        } catch (Exception e) {
            log.error("推荐过程发生严重错误", e);
            return "{\"error\": \"系统内部错误: " + e.getMessage() + "\"}";
        }
    }
}
