package group.careerservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.careerservice.domain.po.JobImportTaskPO;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface JobImportTaskMapper extends BaseMapper<JobImportTaskPO> {
}
