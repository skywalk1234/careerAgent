package group.resumeparserservice.controller;/* I love coding */

import group.resumeparserservice.domain.vo.ChatMessageListVO;
import group.resumeparserservice.domain.vo.ChatSessionListVO;
import group.resumeparserservice.service.ChatHistoryService;
import group.common.Result;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 聊天历史控制器
 * 管理用户与AI助手的会话历史和消息记录
 */
@RestController
@RequestMapping("/users/me/home/assistant")
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryController {

    private final ChatHistoryService chatHistoryService;

    /**
     * 9.3.1 获取会话列表
     * GET /users/me/home/assistant/sessions
     *
     * @return 会话列表
     */
    @GetMapping("/sessions")
    public Result getSessionList() {
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        log.info("获取用户会话列表，用户ID: {}", userId);

        try {
            ChatSessionListVO sessionListVO = chatHistoryService.getSessionList(userId);
            return Result.success(sessionListVO, "会话列表获取成功");
        } catch (Exception e) {
            log.error("获取会话列表失败", e);
            return Result.error(500, "获取会话列表失败: " + e.getMessage());
        }
    }

    /**
     * 获取会话消息列表
     * GET /users/me/home/assistant/sessions/:sessionId/messages
     *
     * @param sessionId 会话ID
     * @return 消息列表
     */
    @GetMapping("/sessions/{sessionId}/messages")
    public Result getMessageList(@PathVariable String sessionId) {
        log.info("获取会话消息列表，会话ID: {}", sessionId);

        try {
            ChatMessageListVO messageListVO = chatHistoryService.getMessageList(sessionId);
            return Result.success(messageListVO, "会话消息获取成功");
        } catch (Exception e) {
            log.error("获取会话消息列表失败", e);
            return Result.error(500, "获取会话消息列表失败: " + e.getMessage());
        }
    }
}
