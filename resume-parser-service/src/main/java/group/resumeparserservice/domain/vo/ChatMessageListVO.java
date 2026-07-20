package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 消息列表响应VO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageListVO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 会话ID
     */
    private String sessionId;

    /**
     * 总消息数
     */
    private Integer total;

    /**
     * 消息列表
     */
    private List<MessageItemVO> list;

    /**
     * 消息项VO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class MessageItemVO implements Serializable {
        private static final long serialVersionUID = 1L;

        private String messageId;
        private String role;
        private String content;
        private String status;
        private LocalDateTime createdAt;
        private List<String> fileNames;
        private List<Map<String, Object>> actions;
        private Object agentTrace;
    }
}
