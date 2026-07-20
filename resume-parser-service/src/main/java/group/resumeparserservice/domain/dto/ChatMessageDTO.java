package group.resumeparserservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 聊天消息DTO
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ChatMessageDTO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 消息ID
     */
    private String messageId;

    /**
     * 角色: user-用户, assistant-助手, system-系统
     */
    private String role;

    /**
     * 消息内容
     */
    private String content;

    /**
     * 处理状态
     */
    private String status;

    /**
     * 创建时间
     */
    private LocalDateTime createdAt;

    /**
     * 文件名列表
     */
    private List<String> fileNames;

    /**
     * 推荐动作列表
     */
    private List<ActionDTO> actions;

    /**
     * 智能体追踪信息
     */
    private Object agentTrace;

    /**
     * 动作DTO
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ActionDTO implements Serializable {
        private static final long serialVersionUID = 1L;

        /**
         * 动作标签
         */
        private String label;

        /**
         * 跳转路由
         */
        private String route;
    }
}
