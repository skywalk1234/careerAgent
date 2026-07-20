package group.careerservice.service.JobExploration;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.FavoriteJob;
import group.careerservice.mapper.FavoriteMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class AddFavoriteJobService {
    private final FavoriteMapper favoriteMapper;
    public Integer addFavoriteJob(String jobId, String userId) {
        FavoriteJob favoriteJob = new FavoriteJob();
        favoriteJob.setJobId(jobId);
        favoriteJob.setUserId(Long.parseLong(userId));
        favoriteJob.setFavoriteAt(LocalDateTime.now());
        int insert = favoriteMapper.insert(favoriteJob);
        log.info("添加收藏岗位成功，岗位ID：{}", jobId);
        return insert;
    }
    public Integer deleteFavoriteJob(String jobId, Long userId) {
        QueryWrapper<FavoriteJob> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("job_id", jobId)
                .eq("user_id", userId);

        int deleteCount = favoriteMapper.delete(queryWrapper);

        log.info("删除收藏岗位成功，岗位ID：{}，删除条数：{}", jobId, deleteCount);
        return deleteCount;
    }
}
