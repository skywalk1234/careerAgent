package group.resumeparserservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.*;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息PO类
 * 对应表: chat_messages
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value = "chat_messages", autoResultMap = true)
public class ChatMessagePO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 业务消息ID
     */
    @TableField("message_id")
    private String messageId;

    /**
     * 关联会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 角色: user-用户, assistant-助手, system-系统
     */
    @TableField("role")
    private String role;

    /**
     * 消息内容
     */
    @TableField("content")
    private String content;

    /**
     * 处理状态: pending-处理中, succeeded-成功, failed-失败
     */
    @TableField("status")
    private String status;

    /**
     * 推荐动作(JSON格式)
     */
    @TableField(value = "actions", typeHandler = JacksonTypeHandler.class)
    private Object actions;

    /**
     * 智能体追踪信息(JSON格式)
     */
    @TableField(value = "agent_trace", typeHandler = JacksonTypeHandler.class)
    private Object agentTrace;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
