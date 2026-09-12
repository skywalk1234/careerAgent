package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 会话列表响应VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatSessionListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 总会话数
     */
    private Integer total;

    /**
     * 会话列表
     */
    private List<SessionItemVO> list;

    /**
     * 会话项VO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SessionItemVO implements Serializable {
        private static final long serialVersionUID = 1L;

        private String sessionId;
        private String title;
        private String lastMessagePreview;
        private Boolean pinned;
        private Boolean favorited;
        private LocalDateTime updatedAt;
    }
}
