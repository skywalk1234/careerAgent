package group.userservice.mapper;/* I love coding */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.userservice.domain.po.User_;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserMapper extends BaseMapper<User_> {
    User_ queryById(int id);
}
