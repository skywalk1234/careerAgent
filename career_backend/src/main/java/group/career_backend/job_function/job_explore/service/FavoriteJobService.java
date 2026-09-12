package group.career_backend.job_function.job_explore.service;

import group.career_backend.job_function.job_explore.domain.response.FavoriteRes;

public interface FavoriteJobService {
    FavoriteRes list(Long userId);

    boolean add(Long userId, String jobId);

    boolean remove(Long userId, String jobId);
}
