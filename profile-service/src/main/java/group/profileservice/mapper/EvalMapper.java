package group.profileservice.mapper;/* I love coding */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.profileservice.domain.po.Evaluation;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface EvalMapper extends BaseMapper<Evaluation> {
}
