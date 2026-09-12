package group.careerservice.service.JobImport;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.common.AsyncTaskErrorStorage;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.dto.JobImportRequestDTO;
import group.careerservice.domain.dto.JobImportStatusDTO;
import group.careerservice.domain.po.JobCategoryCachePO;
import group.careerservice.domain.po.JobImportTaskPO;
import group.careerservice.mapper.JobCategoryCacheMapper;
import group.careerservice.mapper.JobImportTaskMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.IndexOperations;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobImportService {

    private final JobImportTaskMapper jobImportTaskMapper;
    private final JobCategoryCacheMapper jobCategoryCacheMapper;
    private final ElasticsearchRestTemplate elasticsearchRestTemplate;
    private final RabbitTemplate rabbitTemplate;
    private final ObjectMapper objectMapper;

    private final Map<String, JobImportTaskPO> taskCache = new ConcurrentHashMap<>();
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

    public String createImportJob(JobImportRequestDTO request) {
        String importJobId = generateImportJobId();
        String batchId = generateBatchId();

        JobImportTaskPO task = new JobImportTaskPO();
        task.setImportJobId(importJobId);
        task.setBatchId(batchId);
        task.setBatchName(request.getBatchName());
        task.setSourceType(request.getSourceType());
        task.setSourceFile(request.getSourceFile());
        task.setStatus("processing");
        task.setTotalRows(0);
        task.setInsertedRows(0);
        task.setUpdatedRows(0);
        task.setSkippedRows(0);
        task.setCreatedAt(LocalDateTime.now());
        task.setUpdatedAt(LocalDateTime.now());

        jobImportTaskMapper.insert(task);
        taskCache.put(importJobId, task);

        Map<String, Object> message = new HashMap<>();
        message.put("importJobId", importJobId);
        message.put("batchId", batchId);
        message.put("request", request);
        rabbitTemplate.convertAndSend("job.import", message);

        log.info("创建岗位导入任务成功，importJobId: {}, batchId: {}", importJobId, batchId);
        return importJobId;
    }

    public JobImportStatusDTO getImportStatus(String importJobId) {
        AsyncTaskErrorStorage.ErrorInfo errorInfo = asyncTaskErrorStorage.getError(importJobId);
        if (errorInfo != null) {
            JobImportStatusDTO errorStatus = new JobImportStatusDTO();
            errorStatus.setImportJobId(importJobId);
            errorStatus.setStatus("error");
            errorStatus.setErrorCode(errorInfo.getErrorCode());
            errorStatus.setErrorMessage(errorInfo.getErrorMessage());
            return errorStatus;
        }

        JobImportTaskPO task = taskCache.get(importJobId);
        if (task == null) {
            task = jobImportTaskMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<JobImportTaskPO>()
                    .eq(JobImportTaskPO::getImportJobId, importJobId)
            );
        }

        if (task == null) {
            return null;
        }

        JobImportStatusDTO status = new JobImportStatusDTO();
        status.setImportJobId(task.getImportJobId());
        status.setStatus(task.getStatus());

        if ("succeeded".equals(task.getStatus()) || "failed".equals(task.getStatus())) {
            JobImportStatusDTO.ImportResult result = new JobImportStatusDTO.ImportResult();
            result.setBatchId(task.getBatchId());
            result.setTotalRows(task.getTotalRows());
            result.setInsertedRows(task.getInsertedRows());
            result.setUpdatedRows(task.getUpdatedRows());
            result.setSkippedRows(task.getSkippedRows());
            status.setResult(result);
        }

        return status;
    }

    public void processImportJob(String importJobId, String batchId, JobImportRequestDTO request) {
        log.info("开始处理岗位导入任务，importJobId: {}, batchId: {}", importJobId, batchId);

        try {
            updateTaskStatus(importJobId, "processing", null);

            List<Map<String, Object>> rawJobs = readSourceFile(request.getSourceFile(), request.getSourceType());

            int totalRows = rawJobs.size();
            int insertedRows = 0;
            int updatedRows = 0;
            int skippedRows = 0;

            ensureIndexExists();

            for (Map<String, Object> rawJob : rawJobs) {
                try {
                    JobDocument jobDocument = normalizeJobData(rawJob, batchId);

                    if (shouldDeduplicate(request, jobDocument)) {
                        if (isDuplicate(jobDocument, request.getImportOptions().getDeduplicateBy())) {
                            skippedRows++;
                            continue;
                        }
                    }

                    if (existsInElasticsearch(jobDocument.getJobId())) {
                        elasticsearchRestTemplate.save(jobDocument);
                        updatedRows++;
                    } else {
                        elasticsearchRestTemplate.save(jobDocument);
                        insertedRows++;
                    }

                    cacheNewCategory(batchId, jobDocument.getJobName());

                } catch (Exception e) {
                    log.error("处理单条岗位数据失败: {}", rawJob.get("jobId"), e);
                    skippedRows++;
                }
            }

            updateTaskResult(importJobId, totalRows, insertedRows, updatedRows, skippedRows, "succeeded");
            log.info("岗位导入任务完成，importJobId: {}, 总计: {}, 插入: {}, 更新: {}, 跳过: {}",
                importJobId, totalRows, insertedRows, updatedRows, skippedRows);

        } catch (Exception e) {
            log.error("岗位导入任务失败，importJobId: {}", importJobId, e);
            updateTaskStatus(importJobId, "failed", e.getMessage());
        }
    }

    private List<Map<String, Object>> readSourceFile(String sourceFile, String sourceType) throws Exception {
        log.info("读取源文件: {}, 类型: {}", sourceFile, sourceType);

        if ("csv".equalsIgnoreCase(sourceType)) {
            return readCsvFile(sourceFile);
        } else if ("json".equalsIgnoreCase(sourceType)) {
            return readJsonFile(sourceFile);
        } else {
            throw new UnsupportedOperationException("不支持的源文件类型: " + sourceType);
        }
    }

    private List<Map<String, Object>> readCsvFile(String sourceFile) throws Exception {
        List<Map<String, Object>> result = new ArrayList<>();

        java.io.InputStream inputStream = getClass().getClassLoader().getResourceAsStream(sourceFile);
        if (inputStream == null) {
            java.io.File file = new java.io.File(sourceFile);
            if (file.exists()) {
                inputStream = new java.io.FileInputStream(file);
            } else {
                throw new RuntimeException("CSV文件未找到: " + sourceFile);
            }
        }

        try (java.io.BufferedReader reader = new java.io.BufferedReader(
                new java.io.InputStreamReader(inputStream, java.nio.charset.StandardCharsets.UTF_8))) {

            String headerLine = reader.readLine();
            if (headerLine == null) {
                return result;
            }

            String[] headers = headerLine.split(",");

            String line;
            while ((line = reader.readLine()) != null) {
                String[] values = line.split(",");
                Map<String, Object> row = new HashMap<>();
                for (int i = 0; i < Math.min(headers.length, values.length); i++) {
                    row.put(headers[i].trim(), values[i].trim());
                }
                result.add(row);
            }
        }

        return result;
    }

    private List<Map<String, Object>> readJsonFile(String sourceFile) throws Exception {
        java.io.InputStream inputStream = getClass().getClassLoader().getResourceAsStream(sourceFile);
        if (inputStream == null) {
            java.io.File file = new java.io.File(sourceFile);
            if (file.exists()) {
                inputStream = new java.io.FileInputStream(file);
            } else {
                throw new RuntimeException("JSON文件未找到: " + sourceFile);
            }
        }

        Map<String, Object> jsonData = objectMapper.readValue(inputStream,
            new TypeReference<Map<String, Object>>() {});

        if (jsonData.containsKey("data")) {
            return (List<Map<String, Object>>) jsonData.get("data");
        }

        return Collections.singletonList(jsonData);
    }

    private JobDocument normalizeJobData(Map<String, Object> rawJob, String batchId) {
        JobDocument job = new JobDocument();

        job.setJobId((String) rawJob.getOrDefault("jobId", generateJobId()));
        job.setJobName((String) rawJob.get("jobName"));
        job.setJobCode((String) rawJob.get("jobCode"));
        job.setCompanyName((String) rawJob.get("companyName"));
        job.setCity((String) rawJob.get("city"));
        job.setDistrict((String) rawJob.get("district"));

        Object industryTags = rawJob.get("industryTags");
        if (industryTags instanceof List) {
            job.setIndustryTags((List<String>) industryTags);
        } else if (industryTags instanceof String) {
            job.setIndustryTags(Arrays.asList(((String) industryTags).split(",")));
        }

        job.setEducationRequirement((String) rawJob.get("educationRequirement"));

        Object salaryMin = rawJob.get("salaryMin");
        if (salaryMin instanceof Number) {
            job.setSalaryMin(((Number) salaryMin).intValue());
        }

        Object salaryMax = rawJob.get("salaryMax");
        if (salaryMax instanceof Number) {
            job.setSalaryMax(((Number) salaryMax).intValue());
        }

        job.setSalaryUnit((String) rawJob.getOrDefault("salaryUnit", "k/月"));

        Object salaryMonths = rawJob.get("salaryMonths");
        if (salaryMonths instanceof Number) {
            job.setSalaryMonths(((Number) salaryMonths).intValue());
        } else {
            job.setSalaryMonths(12);
        }

        Object salaryNegotiable = rawJob.get("salaryNegotiable");
        job.setSalaryNegotiable(salaryNegotiable instanceof Boolean ? (Boolean) salaryNegotiable : false);

        job.setSalaryNormalized((String) rawJob.get("salaryNormalized"));
        job.setUpdatedAtRaw((String) rawJob.get("updatedAtRaw"));
        job.setUpdatedAtNormalized((String) rawJob.get("updatedAtNormalized"));
        job.setSourceUrl((String) rawJob.get("sourceUrl"));
        job.setSourceSite((String) rawJob.get("sourceSite"));
        job.setCompanySize((String) rawJob.get("companySize"));
        job.setCompanyType((String) rawJob.get("companyType"));
        job.setLevel((String) rawJob.get("level"));
        job.setJobDescription((String) rawJob.get("jobDescription"));
        job.setCompanyBrief((String) rawJob.get("companyBrief"));
        job.setCompanyDescription((String) rawJob.get("companyDescription"));

        Map<String, Object> abilityMap = (Map<String, Object>) rawJob.get("abilityRequirements");
        if (abilityMap != null) {
            JobDocument.AbilityRequirements ability = new JobDocument.AbilityRequirements();
            ability.setProfessionalSkill(getIntValue(abilityMap, "professionalSkill"));
            ability.setCertificate(getIntValue(abilityMap, "certificate"));
            ability.setInnovation(getIntValue(abilityMap, "innovation"));
            ability.setInternalMotivation(getIntValue(abilityMap, "internalMotivation"));
            ability.setLearning(getIntValue(abilityMap, "learning"));
            ability.setStressTolerance(getIntValue(abilityMap, "stressTolerance"));
            ability.setCommunication(getIntValue(abilityMap, "communication"));
            ability.setInternship(getIntValue(abilityMap, "internship"));
            ability.setLanguage(getIntValue(abilityMap, "language"));
            ability.setLeadership(getIntValue(abilityMap, "leadership"));
            ability.setAdaptability(getIntValue(abilityMap, "adaptability"));
            ability.setExecution(getIntValue(abilityMap, "execution"));
            job.setAbilityRequirements(ability);
        }

        Map<String, String> dimensionDetails = (Map<String, String>) rawJob.get("dimensionDetails");
        if (dimensionDetails != null) {
            job.setDimensionDetails(dimensionDetails);
        }

        job.setCreatedAt(LocalDateTime.now().toString());

        return job;
    }

    private Integer getIntValue(Map<String, Object> map, String key) {
        Object value = map.get(key);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        return 0;
    }

    private boolean shouldDeduplicate(JobImportRequestDTO request, JobDocument job) {
        return request.getImportOptions() != null
            && request.getImportOptions().getDeduplicateBy() != null
            && !request.getImportOptions().getDeduplicateBy().isEmpty();
    }

    private boolean isDuplicate(JobDocument job, List<String> deduplicateFields) {
        return false;
    }

    private boolean existsInElasticsearch(String jobId) {
        return elasticsearchRestTemplate.exists(jobId, JobDocument.class);
    }

    private void cacheNewCategory(String batchId, String jobName) {
        if (jobName == null) {
            return;
        }

        boolean isExistingCategory = DEFAULT_JOB_CATEGORIES.stream()
            .anyMatch(cat -> cat.equalsIgnoreCase(jobName) || jobName.contains(cat) || cat.contains(jobName));

        if (!isExistingCategory) {
            JobCategoryCachePO cache = new JobCategoryCachePO();
            cache.setBatchId(batchId);
            cache.setCategoryName(jobName);
            cache.setDescription("新发现的岗位类别，来自批次: " + batchId);
            cache.setCreatedAt(LocalDateTime.now());
            cache.setUpdatedAt(LocalDateTime.now());

            try {
                jobCategoryCacheMapper.insert(cache);
                log.info("缓存新岗位类别: {}, batchId: {}", jobName, batchId);
            } catch (Exception e) {
                log.warn("缓存岗位类别失败，可能已存在: {}", jobName);
            }
        }
    }

    private void ensureIndexExists() {
        IndexOperations indexOps = elasticsearchRestTemplate.indexOps(JobDocument.class);
        if (!indexOps.exists()) {
            indexOps.create();
            indexOps.putMapping(indexOps.createMapping());
            log.info("Elasticsearch索引创建成功");
        }
    }

    private void updateTaskStatus(String importJobId, String status, String errorMessage) {
        JobImportTaskPO task = new JobImportTaskPO();
        task.setStatus(status);
        task.setErrorMessage(errorMessage);
        task.setUpdatedAt(LocalDateTime.now());

        jobImportTaskMapper.update(task, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<JobImportTaskPO>()
            .eq(JobImportTaskPO::getImportJobId, importJobId));

        JobImportTaskPO cached = taskCache.get(importJobId);
        if (cached != null) {
            cached.setStatus(status);
            cached.setErrorMessage(errorMessage);
            cached.setUpdatedAt(LocalDateTime.now());
        }
    }

    private void updateTaskResult(String importJobId, int totalRows, int insertedRows,
                                   int updatedRows, int skippedRows, String status) {
        JobImportTaskPO task = new JobImportTaskPO();
        task.setStatus(status);
        task.setTotalRows(totalRows);
        task.setInsertedRows(insertedRows);
        task.setUpdatedRows(updatedRows);
        task.setSkippedRows(skippedRows);
        task.setUpdatedAt(LocalDateTime.now());
        task.setCompletedAt(LocalDateTime.now());

        jobImportTaskMapper.update(task, new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<JobImportTaskPO>()
            .eq(JobImportTaskPO::getImportJobId, importJobId));

        JobImportTaskPO cached = taskCache.get(importJobId);
        if (cached != null) {
            cached.setStatus(status);
            cached.setTotalRows(totalRows);
            cached.setInsertedRows(insertedRows);
            cached.setUpdatedRows(updatedRows);
            cached.setSkippedRows(skippedRows);
            cached.setUpdatedAt(LocalDateTime.now());
            cached.setCompletedAt(LocalDateTime.now());
        }
    }

    private String generateImportJobId() {
        return "jg_imp_" + System.currentTimeMillis();
    }

    private String generateBatchId() {
        return "batch_" + LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
    }

    private String generateJobId() {
        return "job_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }
}
