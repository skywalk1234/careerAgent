package group.careerservice.service.JobExploration;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.careerservice.domain.po.FavoriteJob;
import group.careerservice.mapper.FavoriteMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
@Slf4j
public class QueryFavoriteJobService {
    @Autowired
    private FavoriteMapper favoriteMapper;
    public List<FavoriteJob> queryFavoriteJobByUserId(Long userId) {
        log.info("查询用户ID={}收藏的所有岗位", userId);

        LambdaQueryWrapper<FavoriteJob> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(FavoriteJob::getUserId, userId)  // 按userId筛选
                .orderByDesc(FavoriteJob::getFavoriteAt);  // 按收藏时间倒序

        List<FavoriteJob> records = favoriteMapper.selectList(queryWrapper);

        log.info("用户ID={}收藏了{}个岗位", userId, records.size());
        return records;
    }
}
