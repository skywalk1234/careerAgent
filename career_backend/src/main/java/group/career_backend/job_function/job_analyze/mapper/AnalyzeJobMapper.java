package group.career_backend.job_function.job_analyze.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.career_backend.job_function.job_analyze.domain.po.AnalyzeJob;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface AnalyzeJobMapper extends BaseMapper<AnalyzeJob> {
}
