package group.resumeparserservice.mapper;/* I love coding */

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import group.resumeparserservice.domain.po.ChatMessagePO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 聊天消息Mapper
 */
@Mapper
public interface ChatMessageMapper extends BaseMapper<ChatMessagePO> {

    /**
     * 根据会话ID查询消息列表
     */
    @Select("SELECT * FROM chat_messages WHERE session_id = #{sessionId} ORDER BY created_at ASC")
    List<ChatMessagePO> selectBySessionId(@Param("sessionId") String sessionId);
}
