package group.career_backend.job_function.job_analyze.service;

import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobRequest;
import group.career_backend.job_function.job_analyze.domain.dto.JobBrief;
import group.career_backend.job_function.job_analyze.domain.po.AnalyzeJob;

import java.util.List;

public interface JobAnalyzeService {
    AnalyzeOutcome analyze(Long userId, AnalyzeJobRequest request);

    AnalyzeJob getDetail(Long userId, Long recordId);

    List<JobBrief> getHistory(Long userId);

    record AnalyzeOutcome(AnalyzeJob record, boolean overwritten, Long removedRecordId) {
    }
}
