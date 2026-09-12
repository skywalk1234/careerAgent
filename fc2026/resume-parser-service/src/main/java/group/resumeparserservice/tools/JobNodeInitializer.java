package group.resumeparserservice.tools;/* I love coding */


import cn.hutool.core.lang.generator.UUIDGenerator;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.dto.JobNodes;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JobNodeInitializer{
    //将岗位类别数据向量化

    private final VectorStore vectorStore;
//    private final EmbeddingModel embeddingModel;
    private final ObjectMapper objectMapper;

    public JobNodeInitializer(
            @Qualifier("pgVectorVectorStore") VectorStore vectorStore,
            ObjectMapper objectMapper) {

        this.vectorStore = vectorStore;
        this.objectMapper = objectMapper;
    }
    // 依赖注入



    public void run() throws Exception {
        System.out.println(">>> [系统启动] 开始初始化岗位节点向量库...");

        // 1. 读取 JSON 数据
        List<JobNodes> jobNodes = loadJobNodesFromJson("job_nodes_simple.json");

        if (CollectionUtils.isEmpty(jobNodes)) {
            System.out.println(">>> [警告] 未读取到岗位数据，跳过入库。");
            return;
        }

        // 2. 转换为 Spring AI Document 列表
        List<Document> documents = jobNodes.stream()
                .map(this::convertToDocument)
                .collect(Collectors.toList());

        // 3. 执行向量化并入库
        // Spring AI 的 PgVectorStore 会自动调用 embeddingModel.embed() 并执行 SQL INSERT
        System.out.println(">>> 正在向量化并写入 " + documents.size() + " 条数据...");
        vectorStore.add(documents);

        System.out.println(">>> [完成] 岗位节点向量库初始化成功！");
    }

    /**
     * 从 classpath 读取 JSON 文件
     */
    private List<JobNodes> loadJobNodesFromJson(String fileName) {
        try {
            ClassPathResource resource = new ClassPathResource(fileName);
            if (!resource.exists()) {
                throw new RuntimeException("文件不存在: " + fileName);
            }
            InputStream inputStream = resource.getInputStream();
            return objectMapper.readValue(inputStream, new TypeReference<List<JobNodes>>() {});
        } catch (Exception e) {
            e.printStackTrace();
            throw new RuntimeException("解析岗位 JSON 文件失败", e);
        }
    }

    /**
     * 核心逻辑：将 JobNodes 转换为 Document
     * 关键点：构建高质量的 content 文本用于向量检索
     */
    private Document convertToDocument(JobNodes node) {

        // --- A. 构建向量化内容 (Content) ---
        // 策略：将分散的字段拼接成一段连贯的自然语言描述。
        // 向量模型对自然语言的语义理解能力远强于对关键词列表的理解。
        StringBuilder sb = new StringBuilder();

        sb.append("岗位类别名称：").append(node.getNodeName()).append(". ");
        sb.append("总结：").append(node.getSummary()).append(". ");
        sb.append("所属职业族：").append(node.getJobFamilyLabel())
                .append(", 职级：").append(node.getLevel()).append(". ");

        // 拼接核心技能
        if (!CollectionUtils.isEmpty(node.getCoreSkills())) {
            sb.append("核心技能要求：").append(String.join(", ", node.getCoreSkills())).append(". ");
        }

        // 拼接技术栈 (工具 + 框架 + 语言)
        List<String> techStack = new ArrayList<>();
        if (!CollectionUtils.isEmpty(node.getFrameworks())) techStack.addAll(node.getFrameworks());
        if (!CollectionUtils.isEmpty(node.getTools())) techStack.addAll(node.getTools());
        if (!CollectionUtils.isEmpty(node.getLanguages())) {
            // 语言要求可能很长，取前 5 个代表性的即可，避免稀释核心语义
            techStack.addAll(node.getLanguages().stream().toList());
        }
        if (!techStack.isEmpty()) {
            sb.append("技术栈包含：").append(String.join(", ", techStack)).append(". ");
        }

        // 拼接硬性要求
        if (node.getRequirements() != null) {
            sb.append("学历和经验要求：").append(node.getRequirements().getHardRequirements())
                    .append(" , ").append(node.getRequirements().getExperienceYears()).append(". ");

            if (!CollectionUtils.isEmpty(node.getRequirements().getMajor())) {
                sb.append("推荐专业：").append(String.join(", ", node.getRequirements().getMajor())).append(". ");
            }
        }

        String contentText = sb.toString();

        // --- B. 构建元数据 (Metadata) ---
        // 将所有原始数据放入 metadata，检索后可直接用于前端展示或逻辑过滤
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("nodeId", node.getNodeId());
//        metadata.put("canonicalJobId", node.getCanonicalJobId());
        metadata.put("nodeName", node.getNodeName());
        metadata.put("jobFamily", node.getJobFamily());
        metadata.put("jobFamilyLabel", node.getJobFamilyLabel());
        metadata.put("level", node.getLevel());
//        metadata.put("industryTags", node.getIndustryTags());
        metadata.put("coreSkills", node.getCoreSkills());
        metadata.put("tools", node.getTools());
        metadata.put("frameworks", node.getFrameworks());
        metadata.put("summary", node.getSummary());

        // 嵌套对象展开或整体存入 (PgVector 支持 JSONB，Spring AI 会自动处理)
        if (node.getRequirements() != null) {
            metadata.put("education", node.getRequirements().getEducation());
            metadata.put("experienceYears", node.getRequirements().getExperienceYears());
            metadata.put("major", node.getRequirements().getMajor());
//            metadata.put("hardRequirements", node.getRequirements().getHardRequirements());
        }

        // 分数信息也存入，虽然不做向量匹配，但可用于后续排序或展示
//        metadata.put("jobExpectedScores", node.get);
//        metadata.put("abilityRequirements", node.getAbilityRequirements());
//        metadata.put("transferabilityScore", node.getTransferabilityScore());

        // --- C. 创建 Document ---
        // 使用 nodeId 作为文档 ID，方便后续去重或更新（取决于 VectorStore 的具体实现策略）
        return new Document(UUID.randomUUID().toString(), contentText, metadata);
    }
}
