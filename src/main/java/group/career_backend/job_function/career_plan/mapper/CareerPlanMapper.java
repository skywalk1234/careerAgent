package group.career_backend.job_function.career_plan.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.career_backend.job_function.career_plan.domain.po.CareerPlan;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CareerPlanMapper extends BaseMapper<CareerPlan> {
}
