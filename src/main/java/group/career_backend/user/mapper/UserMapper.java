package group.career_backend.user.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.career_backend.user.domain.po.User_;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User_> {
}
