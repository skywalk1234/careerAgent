package group.careerservice;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.domain.dto.JobDocument;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.test.context.ActiveProfiles;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Map;

/**
 * 一次性将完整版岗位数据（jobs_detail.json，4153 条，与向量库同源）导入 Elasticsearch jobs_index。
 *
 * <p>背景：推荐走 pgvector（完整版 4153 条），详情查询走 ES（此前仅导入简单版 55 条），
 * 导致「能推荐出来但点详情查不到」。本测试把完整版灌入 ES，使两边 jobId 对齐。</p>
 *
 * <p>用法：在 IDE 中直接运行本测试方法 importFullJobsIntoEs（幂等，重复运行只更新）。</p>
 */
@SpringBootTest
@ActiveProfiles("test")
public class JobFullImportTest {

    // 与向量库同源的完整版岗位数据（位于 resume-parser-service 资源目录，按实际路径调整）
    private static final String FULL_JOBS_FILE =
            "D:/desktop/university/career_agent/fc2026/resume-parser-service/src/main/resources/jobs_detail.json";

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Test
    public void importFullJobsIntoEs() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        // 1. 读取完整版岗位数据（顶层数组结构）
        List<Map<String, Object>> jobs = readFullJobs(objectMapper);
        System.out.println("读取到完整版岗位数据: " + jobs.size() + " 条");
        if (jobs.isEmpty()) {
            return;
        }

        // 2. 确保索引存在
        IndexOperations indexOps = elasticsearchOperations.indexOps(JobDocument.class);
        if (!indexOps.exists()) {
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping());
            System.out.println("索引 jobs_index 创建成功");
        } else {
            System.out.println("索引 jobs_index 已存在");
        }

        // 3. 逐条 upsert（按 jobId）
        int inserted = 0;
        int updated = 0;
        int failed = 0;
        for (Map<String, Object> jobData : jobs) {
            try {
                String jobId = String.valueOf(jobData.get("jobId"));
                JobDocument doc = convertToJobDocument(jobData);
                boolean existed = elasticsearchOperations.exists(jobId, JobDocument.class);
                elasticsearchOperations.save(doc);
                if (existed) {
                    updated++;
                } else {
                    inserted++;
                }
            } catch (Exception e) {
                failed++;
                System.err.println("导入失败: jobId=" + jobData.get("jobId") + " -> " + e.getMessage());
            }
        }

        // 4. 统计 + 验证
        System.out.println("===== 完整版导入完成 =====");
        System.out.println("总计: " + jobs.size() + ", 新增: " + inserted + ", 更新: " + updated + ", 失败: " + failed);
        long count = elasticsearchOperations.count(
                org.springframework.data.elasticsearch.core.query.Query.findAll(), JobDocument.class);
        System.out.println("jobs_index 当前文档数: " + count);

        String verifyId = "job_frontend_junior_afb4d7";
        System.out.println("验证 " + verifyId + " 是否可查: " + elasticsearchOperations.exists(verifyId, JobDocument.class));
    }

    private List<Map<String, Object>> readFullJobs(ObjectMapper objectMapper) throws Exception {
        // 优先 classpath（文件拷入本模块时），否则文件系统绝对路径
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("jobs_detail.json");
        if (inputStream != null) {
            try (inputStream) {
                return objectMapper.readValue(inputStream, new TypeReference<List<Map<String, Object>>>() {});
            }
        }
        Path path = Paths.get(FULL_JOBS_FILE);
        if (Files.exists(path)) {
            try (InputStream is = Files.newInputStream(path)) {
                return objectMapper.readValue(is, new TypeReference<List<Map<String, Object>>>() {});
            }
        }
        throw new RuntimeException("完整版岗位数据文件未找到: " + FULL_JOBS_FILE);
    }

    /**
     * 将 Map 数据转换为 JobDocument 对象（与 JobDocumentIndexingTest 保持一致）
     */
    private JobDocument convertToJobDocument(Map<String, Object> jobData) {
        JobDocument jobDocument = new JobDocument();

        // 设置基本字段
        jobDocument.setJobId((String) jobData.get("jobId"));
        jobDocument.setJobName((String) jobData.get("jobName"));
        jobDocument.setJobCode((String) jobData.get("jobCode"));
        jobDocument.setCompanyName((String) jobData.get("companyName"));
        jobDocument.setCity((String) jobData.get("city"));
        jobDocument.setDistrict((String) jobData.get("district"));

        // 设置列表类型字段
        if (jobData.get("industryTags") instanceof List) {
            jobDocument.setIndustryTags((List<String>) jobData.get("industryTags"));
        }

        // 设置教育和薪资相关字段
        jobDocument.setEducationRequirement((String) jobData.get("educationRequirement"));

        Object salaryMin = jobData.get("salaryMin");
        if (salaryMin instanceof Number) {
            jobDocument.setSalaryMin(((Number) salaryMin).intValue());
        }

        Object salaryMax = jobData.get("salaryMax");
        if (salaryMax instanceof Number) {
            jobDocument.setSalaryMax(((Number) salaryMax).intValue());
        }

        jobDocument.setSalaryUnit((String) jobData.get("salaryUnit"));

        Object salaryMonths = jobData.get("salaryMonths");
        if (salaryMonths instanceof Number) {
            jobDocument.setSalaryMonths(((Number) salaryMonths).intValue());
        }

        jobDocument.setSalaryNegotiable((Boolean) jobData.get("salaryNegotiable"));
        jobDocument.setSalaryNormalized((String) jobData.get("salaryNormalized"));

        // 设置更新时间和来源信息
        jobDocument.setUpdatedAtRaw((String) jobData.get("updatedAtRaw"));
        jobDocument.setUpdatedAtNormalized((String) jobData.get("updatedAtNormalized"));
        jobDocument.setSourceUrl((String) jobData.get("sourceUrl"));
        jobDocument.setSourceSite((String) jobData.get("sourceSite"));

        // 设置公司信息
        jobDocument.setCompanySize((String) jobData.get("companySize"));
        jobDocument.setCompanyType((String) jobData.get("companyType"));
        jobDocument.setLevel((String) jobData.get("level"));

        // 设置描述字段
        jobDocument.setJobDescription((String) jobData.get("jobDescription"));
        jobDocument.setCompanyBrief((String) jobData.get("companyBrief"));
        jobDocument.setCompanyDescription((String) jobData.get("companyDescription"));

        // 设置能力要求对象
        Map<String, Object> abilityMap = (Map<String, Object>) jobData.get("abilityRequirements");
        if (abilityMap != null) {
            JobDocument.AbilityRequirements abilityRequirements = new JobDocument.AbilityRequirements();
            setAbilityValue(abilityMap, "professionalSkill", abilityRequirements::setProfessionalSkill);
            setAbilityValue(abilityMap, "certificate", abilityRequirements::setCertificate);
            setAbilityValue(abilityMap, "innovation", abilityRequirements::setInnovation);
            setAbilityValue(abilityMap, "internalMotivation", abilityRequirements::setInternalMotivation);
            setAbilityValue(abilityMap, "learning", abilityRequirements::setLearning);
            setAbilityValue(abilityMap, "stressTolerance", abilityRequirements::setStressTolerance);
            setAbilityValue(abilityMap, "communication", abilityRequirements::setCommunication);
            setAbilityValue(abilityMap, "internship", abilityRequirements::setInternship);
            setAbilityValue(abilityMap, "language", abilityRequirements::setLanguage);
            setAbilityValue(abilityMap, "leadership", abilityRequirements::setLeadership);
            setAbilityValue(abilityMap, "adaptability", abilityRequirements::setAdaptability);
            setAbilityValue(abilityMap, "execution", abilityRequirements::setExecution);
            jobDocument.setAbilityRequirements(abilityRequirements);
        }

        // 设置维度详情对象
        Map<String, String> dimensionDetails = (Map<String, String>) jobData.get("dimensionDetails");
        if (dimensionDetails != null) {
            jobDocument.setDimensionDetails(dimensionDetails);
        }

        // 设置创建时间
        jobDocument.setCreatedAt((String) jobData.get("createdAt"));

        return jobDocument;
    }

    /**
     * 辅助方法：设置能力值
     */
    private void setAbilityValue(Map<String, Object> abilityMap, String fieldName,
                                 java.util.function.Consumer<Integer> setter) {
        Object value = abilityMap.get(fieldName);
        if (value instanceof Number) {
            setter.accept(((Number) value).intValue());
        }
    }
}
