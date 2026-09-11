package group.career_backend.job_function.job_explore.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.career_backend.job_function.job_explore.domain.po.FavoriteJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface FavoriteJobMapper extends BaseMapper<FavoriteJob> {
}
