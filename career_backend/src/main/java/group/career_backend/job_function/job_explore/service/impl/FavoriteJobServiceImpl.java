package group.career_backend.job_function.job_explore.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.career_backend.job_function.job_explore.domain.dto.JobVectorItem;
import group.career_backend.job_function.job_explore.domain.po.FavoriteJob;
import group.career_backend.job_function.job_explore.domain.response.FavoriteRes;
import group.career_backend.job_function.job_explore.mapper.FavoriteJobMapper;
import group.career_backend.job_function.job_explore.repository.JobVectorRepository;
import group.career_backend.job_function.job_explore.service.FavoriteJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class FavoriteJobServiceImpl implements FavoriteJobService {

    private final FavoriteJobMapper favoriteJobMapper;
    private final JobVectorRepository jobVectorRepository;

    @Override
    public FavoriteRes list(Long userId) {
        log.info("[业务处理] 开始查询用户收藏, userId={}", userId);
        List<FavoriteJob> favorites = favoriteJobMapper.selectList(new LambdaQueryWrapper<FavoriteJob>()
                .eq(FavoriteJob::getUserId, userId)
                .orderByDesc(FavoriteJob::getFavoriteAt)
                .orderByDesc(FavoriteJob::getId));
        Map<String, JobVectorItem> jobs = jobVectorRepository.findByJobKeys(
                favorites.stream().map(FavoriteJob::getJobId).toList());

        List<FavoriteRes.JobInfo> items = new ArrayList<>();
        for (FavoriteJob favorite : favorites) {
            JobVectorItem job = jobs.get(favorite.getJobId());
            if (job == null) {
                log.warn("[业务处理] 收藏岗位在PG中不存在, userId={}, jobId={}", userId, favorite.getJobId());
                continue;
            }
            FavoriteRes.JobInfo item = new FavoriteRes.JobInfo();
            item.setJobId(job.getJobId());
            item.setJobName(job.getJobName());
            item.setCompanyName(job.getCompanyName());
            item.setCity(job.getCity());
            item.setEdu(job.getEdu());
            item.setSalaryText(job.getSalaryText());
            item.setUpdatedAtRaw(job.getUpdatedAtRaw());
            item.setFavoritedAt(favorite.getFavoriteAt() == null ? null : favorite.getFavoriteAt().toString());
            items.add(item);
        }

        FavoriteRes response = new FavoriteRes();
        response.setList(items);
        response.setTotal(items.size());
        log.info("[业务处理] 用户收藏查询完成, userId={}, records={}, returned={}",
                userId, favorites.size(), items.size());
        return response;
    }

    @Override
    @Transactional
    public boolean add(Long userId, String jobId) {
        if (!StringUtils.hasText(jobId)) {
            log.info("[业务处理] 收藏岗位参数为空, userId={}", userId);
            return false;
        }
        String normalizedJobId = jobId.trim();
        log.info("[业务处理] 开始添加收藏, userId={}, jobId={}", userId, normalizedJobId);
        FavoriteJob favorite = new FavoriteJob();
        favorite.setUserId(userId);
        favorite.setJobId(normalizedJobId);
        favorite.setFavoriteAt(LocalDateTime.now());
        boolean added = favoriteJobMapper.insert(favorite) > 0;
        log.info("[业务处理] 添加收藏完成, userId={}, jobId={}, added={}", userId, normalizedJobId, added);
        return added;
    }

    @Override
    @Transactional
    public boolean remove(Long userId, String jobId) {
        log.info("[业务处理] 开始取消收藏, userId={}, jobId={}", userId, jobId);
        int deleted = favoriteJobMapper.delete(new LambdaQueryWrapper<FavoriteJob>()
                .eq(FavoriteJob::getUserId, userId)
                .eq(FavoriteJob::getJobId, jobId));
        log.info("[业务处理] 取消收藏完成, userId={}, jobId={}, deleted={}", userId, jobId, deleted);
        return deleted > 0;
    }
}
