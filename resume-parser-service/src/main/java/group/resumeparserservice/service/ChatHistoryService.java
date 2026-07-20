package group.resumeparserservice.service;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.resumeparserservice.domain.po.ChatMessagePO;
import group.resumeparserservice.domain.po.ChatSessionPO;
import group.resumeparserservice.domain.vo.ChatMessageListVO;
import group.resumeparserservice.domain.vo.ChatSessionListVO;
import group.resumeparserservice.mapper.ChatMessageMapper;
import group.resumeparserservice.mapper.ChatSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 聊天历史服务
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ChatHistoryService {

    private final ChatSessionMapper chatSessionMapper;
    private final ChatMessageMapper chatMessageMapper;

    /**
     * 获取用户的会话列表
     *
     * @param userId 用户ID
     * @return 会话列表VO
     */
    public ChatSessionListVO getSessionList(String userId) {
        log.info("获取用户会话列表，用户ID: {}", userId);

        // 查询用户的所有会话，按置顶和更新时间排序
        QueryWrapper<ChatSessionPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId)
                .orderByDesc("is_pinned")
                .orderByDesc("updated_at");

        List<ChatSessionPO> sessionPOList = chatSessionMapper.selectList(queryWrapper);

        // 转换为VO
        List<ChatSessionListVO.SessionItemVO> sessionItemVOList = sessionPOList.stream()
                .map(this::convertToSessionItemVO)
                .collect(Collectors.toList());

        return ChatSessionListVO.builder()
                .total(sessionItemVOList.size())
                .list(sessionItemVOList)
                .build();
    }

    /**
     * 获取会话的消息列表
     *
     * @param sessionId 会话ID
     * @return 消息列表VO
     */
    public ChatMessageListVO getMessageList(String sessionId) {
        log.info("获取会话消息列表，会话ID: {}", sessionId);

        // 查询会话的所有消息，按创建时间升序
        List<ChatMessagePO> messagePOList = chatMessageMapper.selectBySessionId(sessionId);

        // 转换为VO
        List<ChatMessageListVO.MessageItemVO> messageItemVOList = messagePOList.stream()
                .map(this::convertToMessageItemVO)
                .collect(Collectors.toList());

        return ChatMessageListVO.builder()
                .sessionId(sessionId)
                .total(messageItemVOList.size())
                .list(messageItemVOList)
                .build();
    }

    /**
     * 将会话PO转换为会话项VO
     */
    private ChatSessionListVO.SessionItemVO convertToSessionItemVO(ChatSessionPO po) {
        return ChatSessionListVO.SessionItemVO.builder()
                .sessionId(po.getSessionId())
                .title(po.getTitle())
                .lastMessagePreview(po.getLastMessagePreview())
                .pinned(po.getPinned())
                .favorited(po.getFavorited())
                .updatedAt(po.getUpdatedAt())
                .build();
    }

    /**
     * 将消息PO转换为消息项VO
     */
    private ChatMessageListVO.MessageItemVO convertToMessageItemVO(ChatMessagePO po) {
        // 解析actions字段为Map列表
        List<Map<String, Object>> actionList = parseActions(po.getActions());

        return ChatMessageListVO.MessageItemVO.builder()
                .messageId(po.getMessageId())
                .role(po.getRole())
                .content(po.getContent())
                .status(po.getStatus())
                .createdAt(po.getCreatedAt())
                .fileNames(Collections.emptyList())
                .actions(actionList)
                .agentTrace(po.getAgentTrace())
                .build();
    }

    /**
     * 解析actions字段为Map列表
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseActions(Object actions) {
        if (actions == null) {
            return Collections.emptyList();
        }

        try {
            // 如果是List类型
            if (actions instanceof List) {
                List<?> actionList = (List<?>) actions;
                return actionList.stream()
                        .filter(action -> action instanceof Map)
                        .map(action -> (Map<String, Object>) action)
                        .collect(Collectors.toList());
            }
        } catch (Exception e) {
            log.warn("解析actions失败: {}", e.getMessage());
        }

        return Collections.emptyList();
    }
}
