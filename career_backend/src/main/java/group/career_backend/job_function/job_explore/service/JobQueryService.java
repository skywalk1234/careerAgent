package group.career_backend.job_function.job_explore.service;

import group.career_backend.job_function.job_explore.domain.dto.JobVectorItem;
import group.career_backend.job_function.job_explore.domain.vo.JobVectorFilter;
import group.career_backend.job_function.job_explore.domain.vo.UserJobCreateRequest;

import java.util.List;
import java.util.Map;

public interface JobQueryService {
    JobVectorItem getById(String jobId);

    Map<String, List<String>> getFilterOptions();

    Map<String, Object> search(JobVectorFilter filter);

    JobVectorItem createUserJob(Long userId, UserJobCreateRequest request);

    void deleteUserJob(String jobId);
}
