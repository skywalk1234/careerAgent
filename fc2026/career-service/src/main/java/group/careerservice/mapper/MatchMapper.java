package group.careerservice.mapper;/* I love coding */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.careerservice.domain.dto.MatchJob;
import group.careerservice.domain.po.MatchJobPo;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface MatchMapper extends BaseMapper<MatchJobPo> {
}
