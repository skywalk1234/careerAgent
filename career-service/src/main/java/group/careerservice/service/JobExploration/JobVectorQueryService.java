package group.careerservice.service.JobExploration;/* I love coding */

import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.dto.JobVectorItem;
import group.careerservice.domain.vo.JobVectorFilter;
import group.careerservice.repository.JobVectorRepository;
import group.careerservice.repository.JobVectorRepository.PageResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位探索（pgvector 版）查询服务：把 {@link JobVectorRepository} 包一层，负责
 * 日志、异常兜底，以及「向量库查不到时回退 ES」的兼容逻辑。
 *
 * <p>回退是刻意的垫片：改造前岗位探索页读的是 ES 的 jobs_index，用户可能已经收藏了那批 jobId，
 * 直接切库会让老岗位的详情/收藏全部变空。回退只在 PG 查不到时发生，成本可接受。
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class JobVectorQueryService {

    private final JobVectorRepository jobVectorRepository;
    private final SaveJobService saveJobService;

    /** 分页查询岗位列表 */
    public PageResult queryJobs(JobVectorFilter filter) {
        log.info("按条件分页查询岗位（向量库），filter: {}", filter);
        PageResult result = jobVectorRepository.queryPage(filter);
        log.info("查询成功，命中：{} 条，本页返回：{} 条", result.total(), result.list().size());
        return result;
    }

    /**
     * 按 jobId 查岗位详情：先查向量库（job_key），查不到再回退 ES（老 jobId）。
     * 两边都没有返回 null。
     */
    public JobVectorItem queryJobById(String jobId) {
        if (!StringUtils.hasText(jobId)) {
            return null;
        }
        String key = jobId.trim();
        JobVectorItem item = jobVectorRepository.findByJobKey(key);
        if (item != null) {
            return item;
        }

        log.info("向量库未命中 jobId: {}，回退查 ES", key);
        JobDocument document = saveJobService.queryJobById(key);
        return document == null ? null : fromEsDocument(document);
    }

    /** 筛选下拉选项，来自向量库里真实存在的取值 */
    public Map<String, List<String>> queryFilterOptions() {
        return jobVectorRepository.loadFilterOptions();
    }

    /**
     * 把 ES 的 {@link JobDocument} 尽力映射成 {@link JobVectorItem}。
     * 两边字段只有一部分能对上，对不上的留 null——回退路径本来就是给老数据兜底的。
     */
    private JobVectorItem fromEsDocument(JobDocument document) {
        JobVectorItem item = new JobVectorItem();
        item.setJobId(document.getJobId());
        item.setJobName(document.getJobName());
        item.setCompanyName(document.getCompanyName());
        item.setCity(document.getCity());
        item.setCats(document.getIndustryTags() == null ? List.of() : document.getIndustryTags());
        item.setSalaryText(document.getSalaryNormalized());
        item.setSalaryMin(document.getSalaryMin() == null ? null : document.getSalaryMin().doubleValue());
        item.setSalaryMax(document.getSalaryMax() == null ? null : document.getSalaryMax().doubleValue());
        item.setSalaryUnit(document.getSalaryUnit());
        // 刻意不把 ES 的 level（junior/middle/senior/lead）塞进 exp：
        // exp 在新契约里是「3-5年」这种经验要求，语义对不上，硬塞会让前端显示成「经验：senior」
        item.setEdu(document.getEducationRequirement());
        item.setSourceSite(document.getSourceSite());
        item.setSourceUrl(document.getSourceUrl());
        item.setUpdatedAtRaw(StringUtils.hasText(document.getUpdatedAtNormalized())
                ? document.getUpdatedAtNormalized() : document.getUpdatedAtRaw());
        item.setIsNew(false);
        item.setVectorReady(false);
        item.setJobDescription(document.getJobDescription());
        return item;
    }

    /** 组装成接口统一返回的 {total, page, pageSize, list} 外壳 */
    public Map<String, Object> toPageResponse(PageResult result, JobVectorFilter filter) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", result.total());
        response.put("page", filter.getPage() != null ? filter.getPage() : 1);
        response.put("pageSize", filter.getPageSize() != null ? filter.getPageSize() : 20);
        response.put("list", result.list());
        return response;
    }
}
