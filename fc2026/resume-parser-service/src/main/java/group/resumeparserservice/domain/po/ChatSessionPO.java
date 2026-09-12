package group.resumeparserservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.*;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天会话PO类
 * 对应表: chat_sessions
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("chat_sessions")
public class ChatSessionPO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private String userId;

    /**
     * 业务会话ID
     */
    @TableField("session_id")
    private String sessionId;

    /**
     * 会话标题
     */
    @TableField("title")
    private String title;

    /**
     * 最后一条消息预览
     */
    @TableField("last_message_preview")
    private String lastMessagePreview;

    /**
     * 是否置顶: 1-是, 0-否
     */
    @TableField("is_pinned")
    private Boolean pinned;

    /**
     * 是否收藏: 1-是, 0-否
     */
    @TableField("is_favorited")
    private Boolean favorited;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
