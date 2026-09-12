package group.resumeparserservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天会话DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 会话标题
     */
    private String title;

    /**
     * 最后一条消息预览
     */
    private String lastMessagePreview;

    /**
     * 是否置顶
     */
    private Boolean pinned;

    /**
     * 是否收藏
     */
    private Boolean favorited;

    /**
     * 更新时间
     */
    private LocalDateTime updatedAt;
}
