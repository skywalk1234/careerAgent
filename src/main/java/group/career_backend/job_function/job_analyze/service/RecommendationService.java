package group.career_backend.job_function.job_analyze.service;

import group.career_backend.job_function.job_analyze.domain.dto.MatchFilter;
import group.career_backend.job_function.job_analyze.domain.dto.RecommendMessage;
import group.career_backend.job_function.job_analyze.domain.po.RecommendJob;

public interface RecommendationService {
    RecommendJob getLatest(Long userId, int topN);

    void requestRecommendation(Long userId, MatchFilter filters);

    void consume(RecommendMessage message);
}
