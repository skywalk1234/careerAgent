package group.resumeparserservice.tools;/* I love coding */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.dto.JobDocumentDTO;
import group.dto.JobNodes;
import lombok.RequiredArgsConstructor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.util.CollectionUtils;

import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

@Service
public class JobSpecificInitializer{
    //将具体岗位数据向量化

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final VectorStore vectorStore;

    public JobSpecificInitializer(@Qualifier("jobDetailVectorStore") VectorStore vectorStore) {
        this.vectorStore = vectorStore;
    }
    // 注入已配置好的 VectorStore

    // 设置从第几条开始处理（用于中断后恢复），默认从第1条开始
    private static final int START_FROM_INDEX = 3308;

    public void run() throws Exception {
        System.out.println("开始初始化职位数据并向量化...");

        try {
            // 1. 读取 JSON 文件
            ClassPathResource resource = new ClassPathResource("jobs_detail.json");
            InputStream inputStream = resource.getInputStream();

            // 2. 解析为 DTO 列表
            List<JobDocumentDTO> jobList = objectMapper.readValue(
                    inputStream,
                    new TypeReference<List<JobDocumentDTO>>() {}
            );

            if (jobList.isEmpty()) {
                System.out.println("未找到职位数据，跳过向量化。");
                return;
            }

            int totalCount = jobList.size();
            System.out.println("总共需要向量化 " + totalCount + " 条职位数据");

            // 检查起始位置
            int startIndex = START_FROM_INDEX - 1; // 转换为0-based索引
            if (startIndex < 0) startIndex = 0;
            if (startIndex >= totalCount) {
                System.out.println("起始位置 " + START_FROM_INDEX + " 已超过总数据量，无需处理。");
                return;
            }

            if (startIndex > 0) {
                System.out.println("从第 " + START_FROM_INDEX + " 条开始处理（已跳过前 " + startIndex + " 条）");
            }
            System.out.println("==============================================");

            // 3. 逐条向量化
            int successCount = 0;
            int failCount = 0;
            int skipCount = 0;

            for (int i = startIndex; i < jobList.size(); i++) {
                JobDocumentDTO job = jobList.get(i);
                String jobCode = job.getJobCode() != null ? job.getJobCode() : "N/A";
                int currentIndex = i + 1;
                int remaining = totalCount - currentIndex;

                System.out.println("\n>>> 正在处理第 " + currentIndex + "/" + totalCount + " 条数据");
                System.out.println("    JobCode: " + jobCode);
                System.out.println("    职位名称: " + job.getJobName());
                System.out.println("    公司名称: " + job.getCompanyName());
                System.out.println("    已处理: " + successCount + " 条, 失败: " + failCount + " 条, 跳过: " + skipCount + " 条, 剩余: " + remaining + " 条");

                // 先转换为 Document 检查内容长度
                Document document = convertToSpringAiDocument(job);
                String content = document.getText();

                // 检查内容长度是否超过2048字符限制
                if (content != null && content.length() > 2048) {
                    skipCount++;
                    System.err.println("    [!] 内容过长（" + content.length() + " 字符），超过2048限制，跳过 - JobCode: " + jobCode);
                    continue;
                }

                // 带重试机制的处理
                boolean processed = processWithRetry(document, jobCode, 3);

                if (processed) {
                    successCount++;
                    System.out.println("    [✓] 向量化成功 - JobCode: " + jobCode);
                } else {
                    failCount++;
                    System.err.println("    [✗] 向量化失败（已重试3次）- JobCode: " + jobCode);
                }

                // 添加延迟，避免请求过于频繁导致连接池问题
                // 每处理10条后多休息一会儿
                if (currentIndex % 10 == 0) {
                    System.out.println("    [休息] 已处理10条，暂停500ms...");
                    Thread.sleep(500);
                } else {
                    Thread.sleep(100);
                }
            }

            System.out.println("\n==============================================");
            System.out.println("向量化任务完成！");
            System.out.println("总计: " + totalCount + " 条");
            System.out.println("成功: " + successCount + " 条");
            System.out.println("失败: " + failCount + " 条");
            System.out.println("跳过(超长): " + skipCount + " 条");

        } catch (Exception e) {
            System.err.println("职位数据初始化失败: " + e.getMessage());
            e.printStackTrace();
            // 根据需求决定是否抛出异常阻止启动
            // throw new RuntimeException(e);
        }
    }

    /**
     * 将业务 DTO 转换为 Spring AI 的 Document 对象
     * 核心策略：
     * 1. content: 拼接所有用于检索的关键文本（职位名、描述、公司详情、技能要求等）
     * 2. metadata: 存放结构化数据（ID、薪资、地点等），用于过滤或展示
     */
    private Document convertToSpringAiDocument(JobDocumentDTO job) {

        // --- 构建检索内容 (Content) ---
        // 将分散的文本字段拼接成一段完整的自然语言文本，利于 Embedding 模型理解语义
//        text-embedding-v2单次输入最多不能超过2048个字符
        StringBuilder contentBuilder = new StringBuilder();
        contentBuilder.append("职位名称：").append(job.getJobName()).append("\n");
        contentBuilder.append("公司名称：").append(job.getCompanyName()).append("\n");
        contentBuilder.append("工作地点：").append(job.getCity()).append(job.getDistrict()).append("\n");
        contentBuilder.append("薪资范围：").append(job.getSalaryNormalized()).append("\n");
        contentBuilder.append("学历要求：").append(job.getEducationRequirement()).append("\n");
        contentBuilder.append("行业标签：").append(String.join(",", job.getIndustryTags())).append("\n");
        contentBuilder.append("职位描述：").append(job.getJobDescription()).append("\n");
        contentBuilder.append("公司简介：").append(job.getCompanyDescription()).append("\n");

        // 添加能力要求维度
        if (job.getDimensionDetails() != null) {
            contentBuilder.append("核心能力要求：");
            job.getDimensionDetails().forEach((k, v) ->
                    contentBuilder.append(k).append(":").append(v).append("; ")
            );
            contentBuilder.append("\n");
        }

        String content = contentBuilder.toString();

        // --- 构建元数据 (Metadata) ---
        // 用于后续搜索时的过滤 (filterExpression) 或结果展示
        // --- 构建元数据 (Metadata) ---
        Map<String, Object> metadata = new HashMap<>();
// 1. 基础标识信息 (String)
        metadata.put("jobId", safeStr(job.getJobId()));
        metadata.put("jobName", safeStr(job.getJobName()));
        metadata.put("jobCode", safeStr(job.getJobCode()));
        metadata.put("level", safeStr(job.getLevel()));

// 2. 公司与地点信息 (String)
        metadata.put("companyName", safeStr(job.getCompanyName()));
        metadata.put("city", safeStr(job.getCity()));
        metadata.put("district", safeStr(job.getDistrict()));
        metadata.put("companySize", safeStr(job.getCompanySize()));

// 3. 薪资信息 (Double & String)
// 即使数据库是 null，这里也会变成 0.0，防止报错
        metadata.put("salaryMin", safeDouble(job.getSalaryMin()));
        metadata.put("salaryMax", safeDouble(job.getSalaryMax()));
        metadata.put("salaryUnit", safeStr(job.getSalaryUnit()));

// 4. 其他要求 (String)
        metadata.put("educationRequirement", safeStr(job.getEducationRequirement()));
        metadata.put("sourceUrl", safeStr(job.getSourceUrl()));
        metadata.put("sourceSite", safeStr(job.getSourceSite()));
// 5. 长文本描述 (策略：如果为 null 则不放入该 key，或者放入空字符串)
// 方案 A: 只有不为 null 时才放入 (节省空间，推荐)
        if (job.getJobDescription() != null && !job.getJobDescription().isEmpty()) {
            metadata.put("jobDescription", job.getJobDescription());
        } else {
            // 如果您的业务逻辑强依赖这个 key 存在，可以改为 put 空字符串
            // metadata.put("jobDescription", "");
        }

        if (job.getCompanyDescription() != null && !job.getCompanyDescription().isEmpty()) {
            metadata.put("companyDescription", job.getCompanyDescription());
        }
        metadata.put("industryTags", safeList(job.getIndustryTags()));
// 2. 处理嵌套对象 (AbilityRequirements) -> 拍平
// 不要直接放 abilityRequirements 对象，而是把它里面的分数拆出来
        if (job.getAbilityRequirements() != null) {
            JobDocumentDTO.AbilityRequirements abilities = job.getAbilityRequirements();

            // 核心能力
            metadata.put("score_professionalSkill", abilities.getProfessionalSkill());
            metadata.put("score_certificate", abilities.getCertificate());
            metadata.put("score_innovation", abilities.getInnovation());
            metadata.put("score_internalMotivation", abilities.getInternalMotivation());

            // 通用素质
            metadata.put("score_learning", abilities.getLearning());
            metadata.put("score_stressTolerance", abilities.getStressTolerance());
            metadata.put("score_communication", abilities.getCommunication());
            metadata.put("score_adaptability", abilities.getAdaptability());
            metadata.put("score_execution", abilities.getExecution());

            // 管理与经验
            metadata.put("score_leadership", abilities.getLeadership());
            metadata.put("score_internship", abilities.getInternship());
            metadata.put("score_language", abilities.getLanguage());
        }

// 3. 处理列表 (dimensionDetails) -> 转为字符串或保留 (如果驱动支持)
        if (job.getDimensionDetails() != null) {

            metadata.put("dimensionDetails", job.getDimensionDetails());
        }

        // 创建 Document
        // id 建议使用业务主键 (jobId)，方便后续去重或删除
        return new Document(UUID.randomUUID().toString(), content, metadata);
    }

    private String safeStr(String val) {
        return val != null ? val : "";
    }

    // 安全获取数值：null 转为 0.0
    private Double safeDouble(Double val) {
        return val != null ? val : 0.0;
    }

    // 安全获取列表：null 转为空列表
    private List<String> safeList(List<String> val) {
        return val != null ? val : new ArrayList<>();
    }

    /**
     * 带重试机制的向量化处理
     * @param document 已转换的 Document
     * @param jobCode 职位编码（用于日志）
     * @param maxRetries 最大重试次数
     * @return 是否处理成功
     */
    private boolean processWithRetry(Document document, String jobCode, int maxRetries) {
        for (int attempt = 1; attempt <= maxRetries; attempt++) {
            try {
                // 单条存储
                vectorStore.add(List.of(document));
                return true; // 成功，直接返回

            } catch (Exception e) {
                System.err.println("        第 " + attempt + "/" + maxRetries + " 次尝试失败: " + e.getClass().getSimpleName());

                if (attempt < maxRetries) {
                    // 计算退避延迟：第1次等1秒，第2次等2秒，第3次等3秒...
                    long backoffDelay = attempt * 1000L;
                    System.out.println("        等待 " + backoffDelay + "ms 后重试...");
                    try {
                        Thread.sleep(backoffDelay);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
            }
        }
        return false; // 所有重试都失败
    }
}
