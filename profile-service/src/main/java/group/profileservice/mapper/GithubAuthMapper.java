package group.profileservice.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.profileservice.domain.po.GithubAuth;
import org.apache.ibatis.annotations.Mapper;

/**
 * GitHub授权信息Mapper接口
 */
@Mapper
public interface GithubAuthMapper extends BaseMapper<GithubAuth> {
}
