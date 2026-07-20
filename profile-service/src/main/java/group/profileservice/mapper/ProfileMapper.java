package group.profileservice.mapper;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.profileservice.domain.po.*;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Mapper
//获取整个画像信息
public interface ProfileMapper extends BaseMapper<ResumeFull> {
}
