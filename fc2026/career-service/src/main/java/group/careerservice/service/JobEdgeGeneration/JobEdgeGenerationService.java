package group.careerservice.service.JobEdgeGeneration;

import com.alibaba.dashscope.aigc.generation.Generation;
import com.alibaba.dashscope.aigc.generation.GenerationParam;
import com.alibaba.dashscope.aigc.generation.GenerationResult;
import com.alibaba.dashscope.common.Message;
import com.alibaba.dashscope.common.Role;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.common.AsyncTaskErrorStorage;
import group.careerservice.config.DashScopeConfig;
import group.careerservice.domain.dto.JobEdgeGenerateRequestDTO;
import group.careerservice.domain.dto.JobEdgeGenerateStatusDTO;
import group.careerservice.domain.po.JobCategoryCachePO;
import group.careerservice.domain.po.JobEdgeGenerateTaskPO;
import group.careerservice.domain.po.JobNodesPO;
import group.careerservice.domain.po.JobPromotionPO;
import group.careerservice.domain.po.JobTransferPO;
import group.careerservice.mapper.JobCategoryCacheMapper;
import group.careerservice.mapper.JobEdgeGenerateTaskMapper;
import group.careerservice.mapper.JobNodesMapper;
import group.careerservice.mapper.JobPromotionMapper;
import group.careerservice.mapper.JobTransferMapper;
import group.dto.JobNodes;
import group.dto.JobPromotion;
import group.dto.JobTransfer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobEdgeGenerationService {

    private final JobEdgeGenerateTaskMapper jobEdgeGenerateTaskMapper;
    private final JobCategoryCacheMapper jobCategoryCacheMapper;
    private final JobNodesMapper jobNodesMapper;
    private final JobPromotionMapper jobPromotionMapper;
    private final JobTransferMapper jobTransferMapper;
    private final RabbitTemplate rabbitTemplate;
    private final Generation dashScopeGeneration;
    private final DashScopeConfig dashScopeConfig;
    private final ObjectMapper objectMapper;

    private final Map<String, JobEdgeGenerateTaskPO> taskCache = new ConcurrentHashMap<>();
    private final AsyncTaskErrorStorage asyncTaskErrorStorage;

    private static final List<String> DEFAULT_JOB_CATEGORIES = Arrays.asList(
        "C/C++_中级系统开发工程师",
        "C/C++_初级软件开发工程师",
        "C/C++_工业系统专家",
        "C/C++_工控与嵌入式控制工程师",
        "C/C++_数据分析与算法助理",
        "C/C++_机器学习/深度学习工程师",
        "C/C++_资深架构开发工程师",
        "Java_中高级后端开发工程师",
        "Java_云原生与分布式架构工程师",
        "Java_全栈与应用系统开发工程师",
        "Java_初级软件开发工程师",
        "Java_国际化或特定领域技术工程师",
        "Java_大数据与数据开发工程师",
        "产品专员/助理",
        "前端开发_全栈与后端融合工程师",
        "前端开发_初级前端开发工程师",
        "前端开发_前端开发实习生",
        "前端开发_前端架构与工程化专家",
        "前端开发_嵌入式与物联网前端工程师",
        "前端开发_数据可视化与图形图像工程师",
        "前端开发_跨端与多平台开发工程师",
        "售后客服",
        "实施工程师",
        "技术支持工程师",
        "招聘专员/助理",
        "测试工程师",
        "硬件测试",
        "科研人员_专职博士后研究员",
        "科研人员_人才引进与资源对接",
        "科研人员_垂直领域专家岗",
        "科研人员_学科带头人/首席科学家",
        "科研人员_科研管理与智库咨询",
        "科研人员_科研辅助与执行层",
        "统计员",
        "网络客服",
        "软件测试",
        "项目专员/助理",
        "项目经理/主管"
    );

    public String createEdgeGenerationJob(JobEdgeGenerateRequestDTO request) {
        String edgeJobId = generateEdgeJobId();

        JobEdgeGenerateTaskPO task = new JobEdgeGenerateTaskPO();
        task.setEdgeJobId(edgeJobId);
        task.setBatchId(request.getBatchId());
        task.setStatus("processing");
        task.setGeneratedEdges(0);
        task.setPromotionEdges(0);
        task.setTransitionEdges(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        jobEdgeGenerateTaskMapper.insert(task);
        taskCache.put(edgeJobId, task);

        Map<String, Object> message = new HashMap<>();
        message.put("edgeJobId", edgeJobId);
        message.put("batchId", request.getBatchId());
        message.put("strategy", request.getStrategy());
        rabbitTemplate.convertAndSend("job.edge.generate", message);

        log.info("创建岗位关系边生成任务成功，edgeJobId: {}, batchId: {}", edgeJobId, request.getBatchId());
        return edgeJobId;
    }

    public JobEdgeGenerateStatusDTO getEdgeGenerationStatus(String edgeJobId) {
        AsyncTaskErrorStorage.ErrorInfo errorInfo = asyncTaskErrorStorage.getError(edgeJobId);
        if (errorInfo != null) {
            JobEdgeGenerateStatusDTO errorStatus = new JobEdgeGenerateStatusDTO();
            errorStatus.setEdgeJobId(edgeJobId);
            errorStatus.setStatus("error");
            errorStatus.setErrorCode(errorInfo.getErrorCode());
            errorStatus.setErrorMessage(errorInfo.getErrorMessage());
            return errorStatus;
        }

        JobEdgeGenerateTaskPO task = taskCache.get(edgeJobId);
        if (task == null) {
            task = jobEdgeGenerateTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<JobEdgeGenerateTaskPO>()
                    .eq(JobEdgeGenerateTaskPO::getEdgeJobId, edgeJobId)
            );
        }

        if (task == null) {
            return null;
        }

        JobEdgeGenerateStatusDTO status = new JobEdgeGenerateStatusDTO();
        status.setEdgeJobId(task.getEdgeJobId());
        status.setStatus(task.getStatus());

        if ("succeeded".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
            JobEdgeGenerateStatusDTO.EdgeResult result = new JobEdgeGenerateStatusDTO.EdgeResult();
            result.setGeneratedEdges(task.getGeneratedEdges());
            result.setPromotionEdges(task.getPromotionEdges());
            result.setTransitionEdges(task.getTransitionEdges());
            result.setUpdatedAt(task.getUpdatedAt() != null ? task.getUpdatedAt().toString() : null);
            status.setResult(result);
        }

        return status;
    }

    public void processEdgeGeneration(String edgeJobId, String batchId, JobEdgeGenerateRequestDTO.EdgeStrategy strategy) {
        log.info("开始处理岗位关系边生成任务，edgeJobId: {}, batchId: {}", edgeJobId, batchId);

        try {
            updateTaskStatus(edgeJobId, "processing", null);

            List<JobCategoryCachePO> newCategories = jobCategoryCacheMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<JobCategoryCachePO>()
                    .eq(JobCategoryCachePO::getBatchId, batchId)
            );

            if (newCategories.isEmpty()) {
                log.warn("该批次没有新的岗位类别，batchId: {}", batchId);
                updateTaskResult(edgeJobId, 0, 0, 0, "succeeded");
                return;
            }

            int totalGeneratedEdges = 0;
            int totalPromotionEdges = 0;
            int totalTransitionEdges = 0;

            for (JobCategoryCachePO category : newCategories) {
                try {
                    EdgeGenerationResult result = generateEdgesForCategory(category, strategy);

                    saveGeneratedEdges(category.getCategoryName(), result);

                    totalGeneratedEdges += result.getTotalEdges();
                    totalPromotionEdges += result.getPromotionEdges();
                    totalTransitionEdges += result.getTransitionEdges();

                } catch (Exception e) {
                    log.error("为类别生成边失败: {}", category.getCategoryName(), e);
                }
            }

            updateTaskResult(edgeJobId, totalGeneratedEdges, totalPromotionEdges, totalTransitionEdges, "succeeded");
            log.info("岗位关系边生成任务完成，edgeJobId: {}, 总边数: {}, 晋升边: {}, 换岗边: {}",
                edgeJobId, totalGeneratedEdges, totalPromotionEdges, totalTransitionEdges);

        } catch (Exception e) {
            log.error("岗位关系边生成任务失败，edgeJobId: {}", edgeJobId, e);
            updateTaskStatus(edgeJobId, "failed", e.getMessage());
        }
    }

    private EdgeGenerationResult generateEdgesForCategory(JobCategoryCachePO category, JobEdgeGenerateRequestDTO.EdgeStrategy strategy) {
        String categoryName = category.getCategoryName();
        String description = category.getDescription();

        log.info("开始为类别生成关系边: {}", categoryName);

        String prompt = buildEdgeGenerationPrompt(categoryName, description, DEFAULT_JOB_CATEGORIES, strategy);

        String aiResponse = callDashScope(prompt);

        return parseAIResponse(aiResponse, categoryName, strategy);
    }

    private String callDashScope(String prompt) {
        try {
            Message message = Message.builder()
                    .role(Role.USER.getValue())
                    .content(prompt)
                    .build();

            GenerationParam param = GenerationParam.builder()
                    .apiKey(dashScopeConfig.getApiKey())
                    .model("qwen-turbo")
                    .messages(java.util.Collections.singletonList(message))
                    .resultFormat(GenerationParam.ResultFormat.MESSAGE)
                    .build();

            GenerationResult result = dashScopeGeneration.call(param);
            return result.getOutput().getChoices().get(0).getMessage().getContent();
        } catch (Exception e) {
            log.error("调用 DashScope API 失败", e);
            throw new RuntimeException("AI 调用失败", e);
        }
    }

    private String buildEdgeGenerationPrompt(String categoryName, String description, 
                                              List<String> existingCategories,
                                              JobEdgeGenerateRequestDTO.EdgeStrategy strategy) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("你是一个专业的职业规划顾问和岗位分析专家。请分析以下新岗位类别与现有38个标准岗位类别之间的关系。\n\n");
        prompt.append("【新岗位类别】\n");
        prompt.append("类别名称: ").append(categoryName).append("\n");
        prompt.append("类别描述: ").append(description != null ? description : "暂无描述").append("\n\n");
        
        prompt.append("【现有标准岗位类别列表】\n");
        for (int i = 0; i < existingCategories.size(); i++) {
            prompt.append(i + 1).append(". ").append(existingCategories.get(i)).append("\n");
        }
        
        prompt.append("\n【任务要求】\n");
        prompt.append("1. 分析新岗位类别与现有类别的技能相似度和层级关系\n");
        prompt.append("2. 识别可能的晋升路径（从低级到高级，或同领域内的成长路径）\n");
        prompt.append("3. 识别可能的换岗路径（技能可迁移的横向转移）\n");
        
        if (strategy.getMinSimilarity() != null) {
            prompt.append("4. 只保留相似度 >= ").append(strategy.getMinSimilarity()).append(" 的关系\n");
        }
        if (strategy.getMaxOutDegree() != null) {
            prompt.append("5. 每个类别的出度最多为 ").append(strategy.getMaxOutDegree()).append(" 条边\n");
        }
        
        prompt.append("\n【输出格式】\n");
        prompt.append("请严格返回以下JSON格式（不要包含markdown标记）：\n");
        prompt.append("{\n");
        prompt.append("  \"promotionPaths\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"source\": \"新岗位类别名称\",\n");
        prompt.append("      \"target\": \"目标岗位类别名称\",\n");
        prompt.append("      \"score\": 0.85,\n");
        prompt.append("      \"reason\": {\n");
        prompt.append("        \"levelFit\": 0.9,\n");
        prompt.append("        \"skillInheritance\": 0.8,\n");
        prompt.append("        \"abilityGrowth\": 0.75,\n");
        prompt.append("        \"domainContinuity\": 0.85,\n");
        prompt.append("        \"missingSkills\": [\"技能1\", \"技能2\"],\n");
        prompt.append("        \"missingAbilities\": [\"能力1\"],\n");
        prompt.append("        \"why\": \"详细的晋升理由说明\"\n");
        prompt.append("      }\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"transitionPaths\": [\n");
        prompt.append("    {\n");
        prompt.append("      \"source\": \"新岗位类别名称\",\n");
        prompt.append("      \"target\": \"目标岗位类别名称\",\n");
        prompt.append("      \"score\": 0.72,\n");
        prompt.append("      \"reason\": {\n");
        prompt.append("        \"bridgeSkills\": [\"桥接技能1\", \"桥接技能2\"],\n");
        prompt.append("        \"missingSkills\": [\"缺失技能1\"],\n");
        prompt.append("        \"missingAbilities\": [\"缺失能力1\"],\n");
        prompt.append("        \"gapCost\": 0.3,\n");
        prompt.append("        \"estimatedDifficulty\": \"medium\",\n");
        prompt.append("        \"why\": \"详细的换岗理由说明\"\n");
        prompt.append("      }\n");
        prompt.append("    }\n");
        prompt.append("  ],\n");
        prompt.append("  \"nodeInfo\": {\n");
        prompt.append("    \"nodeId\": \"自定义节点ID\",\n");
        prompt.append("    \"nodeName\": \"新岗位类别名称\",\n");
        prompt.append("    \"jobFamily\": \"所属岗位族\",\n");
        prompt.append("    \"jobFamilyLabel\": \"岗位族标签\",\n");
        prompt.append("    \"level\": \"岗位级别(junior/mid/senior/expert)\",\n");
        prompt.append("    \"industryTags\": [\"行业标签1\", \"行业标签2\"],\n");
        prompt.append("    \"coreSkills\": [\"核心技能1\", \"核心技能2\"],\n");
        prompt.append("    \"tools\": [\"工具1\", \"工具2\"],\n");
        prompt.append("    \"frameworks\": [\"框架1\"],\n");
        prompt.append("    \"languages\": [\"编程语言1\"],\n");
        prompt.append("    \"summary\": \"岗位摘要描述\"\n");
        prompt.append("  }\n");
        prompt.append("}\n");
        
        return prompt.toString();
    }

    private EdgeGenerationResult parseAIResponse(String aiResponse, String categoryName, 
                                                  JobEdgeGenerateRequestDTO.EdgeStrategy strategy) {
        EdgeGenerationResult result = new EdgeGenerationResult();
        
        try {
            String cleanJson = aiResponse.replaceAll("```json", "").replaceAll("```", "").trim();
            
            Map<String, Object> responseMap = objectMapper.readValue(cleanJson, Map.class);
            
            List<Map<String, Object>> promotionPaths = (List<Map<String, Object>>) responseMap.get("promotionPaths");
            List<Map<String, Object>> transitionPaths = (List<Map<String, Object>>) responseMap.get("transitionPaths");
            Map<String, Object> nodeInfo = (Map<String, Object>) responseMap.get("nodeInfo");
            
            if (strategy.getIncludePromotion() == null || strategy.getIncludePromotion()) {
                for (Map<String, Object> path : promotionPaths) {
                    JobPromotion promotion = new JobPromotion();
                    promotion.setSource((String) path.get("source"));
                    promotion.setTarget((String) path.get("target"));
                    promotion.setScore(getDoubleValue(path, "score"));
                    
                    Map<String, Object> reasonMap = (Map<String, Object>) path.get("reason");
                    if (reasonMap != null) {
                        JobPromotion.ReasonInfo reason = new JobPromotion.ReasonInfo();
                        reason.setLevelFit(getDoubleValue(reasonMap, "levelFit"));
                        reason.setSkillInheritance(getDoubleValue(reasonMap, "skillInheritance"));
                        reason.setAbilityGrowth(getDoubleValue(reasonMap, "abilityGrowth"));
                        reason.setDomainContinuity(getDoubleValue(reasonMap, "domainContinuity"));
                        reason.setMissingSkills((List<String>) reasonMap.get("missingSkills"));
                        reason.setMissingAbilities((List<String>) reasonMap.get("missingAbilities"));
                        reason.setWhy((String) reasonMap.get("why"));
                        promotion.setReason(reason);
                    }
                    
                    result.addPromotion(promotion);
                }
            }
            
            if (strategy.getIncludeTransition() == null || strategy.getIncludeTransition()) {
                for (Map<String, Object> path : transitionPaths) {
                    JobTransfer transfer = new JobTransfer();
                    transfer.setSource((String) path.get("source"));
                    transfer.setTarget((String) path.get("target"));
                    transfer.setScore(getDoubleValue(path, "score"));
                    
                    Map<String, Object> reasonMap = (Map<String, Object>) path.get("reason");
                    if (reasonMap != null) {
                        JobTransfer.TransferReasonInfo reason = new JobTransfer.TransferReasonInfo();
                        reason.setBridgeSkills((List<String>) reasonMap.get("bridgeSkills"));
                        reason.setMissingSkills((List<String>) reasonMap.get("missingSkills"));
                        reason.setMissingAbilities((List<String>) reasonMap.get("missingAbilities"));
                        reason.setGapCost(getDoubleValue(reasonMap, "gapCost"));
                        reason.setEstimatedDifficulty((String) reasonMap.get("estimatedDifficulty"));
                        reason.setWhy((String) reasonMap.get("why"));
                        transfer.setReason(reason);
                    }
                    
                    result.addTransition(transfer);
                }
            }
            
            if (nodeInfo != null) {
                JobNodes node = new JobNodes();
                node.setNodeId((String) nodeInfo.get("nodeId"));
                node.setNodeName((String) nodeInfo.get("nodeName"));
                node.setJobFamily((String) nodeInfo.get("jobFamily"));
                node.setJobFamilyLabel((String) nodeInfo.get("jobFamilyLabel"));
                node.setLevel((String) nodeInfo.get("level"));
                node.setIndustryTags((List<String>) nodeInfo.get("industryTags"));
                node.setCoreSkills((List<String>) nodeInfo.get("coreSkills"));
                node.setTools((List<String>) nodeInfo.get("tools"));
                node.setFrameworks((List<String>) nodeInfo.get("frameworks"));
                node.setLanguages((List<String>) nodeInfo.get("languages"));
                node.setSummary((String) nodeInfo.get("summary"));
                result.setNodeInfo(node);
            }
            
        } catch (Exception e) {
            log.error("解析AI响应失败: {}", aiResponse, e);
        }
        
        return result;
    }

    private Double getDoubleValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        return 0.0;
    }

    private void saveGeneratedEdges(String categoryName, EdgeGenerationResult result) {
        if (result.getNodeInfo() != null) {
            JobNodesPO nodePO = new JobNodesPO();
            nodePO.setNodeId(result.getNodeInfo().getNodeId() != null ? 
                result.getNodeInfo().getNodeId() : generateNodeId(categoryName));
            nodePO.setNodeName(categoryName);
            nodePO.setCanonicalJobId(result.getNodeInfo().getCanonicalJobId());
            nodePO.setMetadata(result.getNodeInfo());
            nodePO.setCreatedAt(LocalDateTime.now());
            nodePO.setUpdatedAt(LocalDateTime.now());
            jobNodesMapper.insert(nodePO);
            log.info("保存新节点: {}", categoryName);
        }

        for (JobPromotion promotion : result.getPromotions()) {
            JobPromotionPO promotionPO = new JobPromotionPO();
            promotionPO.setSource(promotion.getSource());
            promotionPO.setTarget(promotion.getTarget());
            promotionPO.setMetadata(promotion);
            promotionPO.setCreatedAt(LocalDateTime.now());
            promotionPO.setUpdatedAt(LocalDateTime.now());
            jobPromotionMapper.insert(promotionPO);
        }

        for (JobTransfer transfer : result.getTransitions()) {
            JobTransferPO transferPO = new JobTransferPO();
            transferPO.setSource(transfer.getSource());
            transferPO.setTarget(transfer.getTarget());
            transferPO.setMetadata(transfer);
            transferPO.setCreatedAt(LocalDateTime.now());
            transferPO.setUpdatedAt(LocalDateTime.now());
            jobTransferMapper.insert(transferPO);
        }

        log.info("保存边完成 - 晋升边: {}, 换岗边: {}", 
            result.getPromotions().size(), result.getTransitions().size());
    }

    private void updateTaskStatus(String edgeJobId, String status, String errorMessage) {
        JobEdgeGenerateTaskPO task = new JobEdgeGenerateTaskPO();
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());

        jobEdgeGenerateTaskMapper.update(task, 
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<JobEdgeGenerateTaskPO>()
                .eq(JobEdgeGenerateTaskPO::getEdgeJobId, edgeJobId));

        JobEdgeGenerateTaskPO cached = taskCache.get(edgeJobId);
        if (cached != null) {
            cached.setStatus(status);
            cached.setErrorMessage(errorMessage);
            cached.setUpdatedAt(LocalDateTime.now());
        }
    }

    private void updateTaskResult(String edgeJobId, int generatedEdges, int promotionEdges, 
                                   int transitionEdges, String status) {
        JobEdgeGenerateTaskPO task = new JobEdgeGenerateTaskPO();
        task.setStatus(status);
        task.setGeneratedEdges(generatedEdges);
        task.setPromotionEdges(promotionEdges);
        task.setTransitionEdges(transitionEdges);
        task.setUpdatedAt(LocalDateTime.now());
        task.setCompletedAt(LocalDateTime.now());

        jobEdgeGenerateTaskMapper.update(task, 
            new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<JobEdgeGenerateTaskPO>()
                .eq(JobEdgeGenerateTaskPO::getEdgeJobId, edgeJobId));

        JobEdgeGenerateTaskPO cached = taskCache.get(edgeJobId);
        if (cached != null) {
            cached.setStatus(status);
            cached.setGeneratedEdges(generatedEdges);
            cached.setPromotionEdges(promotionEdges);
            cached.setTransitionEdges(transitionEdges);
            cached.setUpdatedAt(LocalDateTime.now());
            cached.setCompletedAt(LocalDateTime.now());
        }
    }

    private String generateEdgeJobId() {
        return "jg_edge_" + System.currentTimeMillis();
    }

    private String generateNodeId(String categoryName) {
        return "node_" + categoryName.hashCode() + "_" + System.currentTimeMillis();
    }

    private static class EdgeGenerationResult {
        private final List<JobPromotion> promotions = new ArrayList<>();
        private final List<JobTransfer> transitions = new ArrayList<>();
        private JobNodes nodeInfo;

        public void addPromotion(JobPromotion promotion) {
            promotions.add(promotion);
        }

        public void addTransition(JobTransfer transition) {
            transitions.add(transition);
        }

        public int getTotalEdges() {
            return promotions.size() + transitions.size();
        }

        public int getPromotionEdges() {
            return promotions.size();
        }

        public int getTransitionEdges() {
            return transitions.size();
        }

        public List<JobPromotion> getPromotions() {
            return promotions;
        }

        public List<JobTransfer> getTransitions() {
            return transitions;
        }

        public JobNodes getNodeInfo() {
            return nodeInfo;
        }

        public void setNodeInfo(JobNodes nodeInfo) {
            this.nodeInfo = nodeInfo;
        }
    }
}
