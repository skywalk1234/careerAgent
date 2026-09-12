package group.careerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.careerservice.domain.po.CityStatsPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface CityStatsMapper extends BaseMapper<CityStatsPO> {
}
