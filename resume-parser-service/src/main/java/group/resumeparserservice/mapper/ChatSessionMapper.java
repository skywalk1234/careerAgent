package group.resumeparserservice.mapper;/* I love coding */


import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.resumeparserservice.domain.po.ChatSessionPO;
import org.apache.ibatis.annotations.Mapper;

/**
 * 聊天会话Mapper
 */
@Mapper
public interface ChatSessionMapper extends BaseMapper<ChatSessionPO> {
}
