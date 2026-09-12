package group.profileservice.mapper;/* I love coding */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.profileservice.domain.po.Resume;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface ResumeMapper extends BaseMapper<Resume> {
}
