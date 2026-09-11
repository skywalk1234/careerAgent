package group.career_backend.job_function.job_analyze.service.impl;

import group.career_backend.exception.CommonException;
import group.career_backend.job_function.job_analyze.service.JobDetailProvider;
import group.career_backend.job_function.job_explore.domain.dto.JobVectorItem;
import group.career_backend.job_function.job_explore.service.JobQueryService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@Slf4j
@RequiredArgsConstructor
public class JobExploreDetailProvider implements JobDetailProvider {
    private final JobQueryService jobQueryService;

    @Override
    public Object getJobDetail(String jobId) {
        JobVectorItem job = jobQueryService.getById(jobId);
        if (job == null) {
            throw new CommonException("岗位不存在", 404);
        }
        log.info("[业务处理] 从job_explore获取岗位详情, jobId={}", jobId);
        return job;
    }
}
