package group.career_backend.job_function.job_explore.service.impl;

import group.career_backend.job_function.job_explore.domain.dto.JobVectorItem;
import group.career_backend.job_function.job_explore.domain.vo.JobVectorFilter;
import group.career_backend.job_function.job_explore.repository.JobVectorRepository;
import group.career_backend.job_function.job_explore.service.JobQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class JobQueryServiceImpl implements JobQueryService {

    private final JobVectorRepository jobVectorRepository;

    @Override
    public JobVectorItem getById(String jobId) {
        log.info("[业务处理] 开始查询岗位详情, jobId={}", jobId);
        JobVectorItem job = jobVectorRepository.findByJobKey(jobId);
        log.info("[业务处理] 岗位详情查询完成, jobId={}, found={}", jobId, job != null);
        return job;
    }

    @Override
    public Map<String, List<String>> getFilterOptions() {
        log.info("[业务处理] 开始查询岗位筛选项");
        Map<String, List<String>> options = jobVectorRepository.loadFilterOptions();
        log.info("[业务处理] 岗位筛选项查询完成");
        return options;
    }

    @Override
    public Map<String, Object> search(JobVectorFilter filter) {
        JobVectorFilter actualFilter = filter == null ? new JobVectorFilter() : filter;
        log.info("[业务处理] 开始筛选岗位, keyword={}, city={}, page={}, pageSize={}, sortBy={}, sortOrder={}",
                actualFilter.getKeyword(), actualFilter.getCity(), actualFilter.getPage(),
                actualFilter.getPageSize(), actualFilter.getSortBy(), actualFilter.getSortOrder());
        JobVectorRepository.PageResult result = jobVectorRepository.queryPage(actualFilter);
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", result.total());
        response.put("page", positiveOrDefault(actualFilter.getPage(), 1));
        response.put("pageSize", positiveOrDefault(actualFilter.getPageSize(), 20));
        response.put("list", result.list());
        log.info("[业务处理] 岗位筛选完成, total={}, returned={}", result.total(), result.list().size());
        return response;
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }
}
