package group.careerservice;/* I love coding */

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
import java.util.List;
import java.util.Map;

@SpringBootTest
@ActiveProfiles("test")
public class JobDocumentIndexingTest {
    //初始化：将jobs_profile_simple中的内容插入到elasticSearch中

    @Autowired
    private ElasticsearchOperations elasticsearchOperations;

    @Test
    public void testIndexJobDocumentsFromJson() throws Exception {
        // 1. 创建索引（如果不存在）
        IndexOperations indexOps = elasticsearchOperations.indexOps(JobDocument.class);
        if (!indexOps.exists()) {
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping());
            System.out.println("索引创建成功");
        } else {
            System.out.println("索引已存在");
        }

        // 2. 读取JSON文件
        ObjectMapper objectMapper = new ObjectMapper();
        InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("jobs_detail_simple.json");

        if (inputStream == null) {
            throw new RuntimeException("JSON文件未找到");
        }

        // 3. 解析JSON数据
        Map<String, Object> jsonData = objectMapper.readValue(inputStream,
                new TypeReference<Map<String, Object>>() {});

        // 获取data数组
        List<Map<String, Object>> dataList = (List<Map<String, Object>>) jsonData.get("data");

        if (dataList == null || dataList.isEmpty()) {
            System.out.println("JSON文件中没有找到data数据");
            return;
        }

        System.out.println("找到 " + dataList.size() + " 条职位数据");

        // 4. 遍历数据并写入Elasticsearch
        int successCount = 0;
        int failCount = 0;

        for (Map<String, Object> jobData : dataList) {
            try {
                // 4.1 将Map转换为JobDocument对象
                JobDocument jobDocument = convertToJobDocument(jobData);

                // 4.2 保存到Elasticsearch
                JobDocument savedDocument = elasticsearchOperations.save(jobDocument);

                if (savedDocument != null) {
                    successCount++;
                    System.out.println("成功写入: " + savedDocument.getJobId() + " - " +
                            savedDocument.getJobName());
                } else {
                    failCount++;
                    System.out.println("写入失败: " + jobData.get("jobId"));
                }

            } catch (Exception e) {
                failCount++;
                System.err.println("处理记录时发生错误: " + jobData.get("jobId"));
                e.printStackTrace();
            }
        }

        // 5. 输出统计结果
        System.out.println("\n===== 索引完成统计 =====");
        System.out.println("总数据量: " + dataList.size());
        System.out.println("成功写入: " + successCount);
        System.out.println("写入失败: " + failCount);
        System.out.println("成功率: " + (dataList.size() > 0 ?
                String.format("%.2f%%", (double) successCount / dataList.size() * 100) : "0%"));
    }

    /**
     * 将Map数据转换为JobDocument对象
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

        // 处理薪资字段 - 注意处理可能的null和浮点数
        Object salaryMin = jobData.get("salaryMin");
        if (salaryMin instanceof Number) {
            jobDocument.setSalaryMin(((Number) salaryMin).intValue());
        } else if (salaryMin instanceof Double) {
            jobDocument.setSalaryMin(((Double) salaryMin).intValue());
        }

        Object salaryMax = jobData.get("salaryMax");
        if (salaryMax instanceof Number) {
            jobDocument.setSalaryMax(((Number) salaryMax).intValue());
        } else if (salaryMax instanceof Double) {
            jobDocument.setSalaryMax(((Double) salaryMax).intValue());
        }

        jobDocument.setSalaryUnit((String) jobData.get("salaryUnit"));

        Object salaryMonths = jobData.get("salaryMonths");
        if (salaryMonths instanceof Number) {
            jobDocument.setSalaryMonths(((Number) salaryMonths).intValue());
        } else if (salaryMonths instanceof Double) {
            jobDocument.setSalaryMonths(((Double) salaryMonths).intValue());
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

            // 设置各个能力值
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
        } else if (value instanceof Double) {
            setter.accept(((Double) value).intValue());
        }
    }

    /**
     * 可选的清理测试方法
     */
    @Test
    public void testDeleteAllJobDocuments() {
        IndexOperations indexOps = elasticsearchOperations.indexOps(JobDocument.class);
        if (indexOps.exists()) {
            // 注意：这会删除所有数据，请谨慎使用
            elasticsearchOperations.delete(JobDocument.class);
            System.out.println("已删除所有文档");
        }
    }

    /**
     * 可选的查询测试方法
     */
    @Test
    public void testCountJobDocuments() {
        long count = elasticsearchOperations.count(org.springframework.data.elasticsearch.core.query.Query.findAll(),
                JobDocument.class);
        System.out.println("当前索引中的文档数量: " + count);
    }
}
