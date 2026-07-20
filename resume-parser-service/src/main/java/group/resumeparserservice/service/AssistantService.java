package group.resumeparserservice.service;/* I love coding */

import com.fasterxml.jackson.databind.ObjectMapper;
import group.resumeparserservice.config.RedisMessageSubscriber;
import group.resumeparserservice.domain.po.ChatMessagePO;
import group.resumeparserservice.domain.po.ChatSessionPO;
import group.resumeparserservice.domain.vo.*;
import group.resumeparserservice.mapper.ChatMessageMapper;
import group.resumeparserservice.mapper.ChatSessionMapper;
import group.resumeparserservice.prompts.AI_agent_prompt;
import group.tool.IdGenerator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import reactor.core.publisher.Flux;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@Slf4j
public class AssistantService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ChatClient streamingChatClient;

    @Autowired
    private AI_steps_planning aiStepsPlanning;

    @Autowired
    private group.resumeparserservice.functions.Functions_calling functionsCalling;

    @Autowired
    private AI_comprehend aiComprehend;

    @Autowired
    private ChatSessionMapper chatSessionMapper;

    @Autowired
    private ChatMessageMapper chatMessageMapper;

    @Autowired
    private TaskControlService taskControlService;

    @Autowired
    private RedisMessageSubscriber redisMessageSubscriber;

    @Autowired
    private ObjectMapper objectMapper;

    public void sendMessage(String sessionId, ChatVO request, String messageId, String userId) {

        // 存储完整请求
        String cacheKey = buildCacheKey(sessionId, messageId);
        redisTemplate.opsForValue().set(cacheKey, request, 300000, TimeUnit.SECONDS);

        // 存储userId到Redis，供后续使用
        String userIdKey = buildUserIdCacheKey(sessionId, messageId);
        redisTemplate.opsForValue().set(userIdKey, userId, 300000, TimeUnit.SECONDS);

        // 存储AgentTaskVO用于查询任务状态
        String taskCacheKey = buildTaskCacheKey(sessionId);
        AgentTaskVO taskVO = AgentTaskVO.builder()
                .goal(request.getContent())
                .mode("auto")
                .priority("normal")
                .build();
        redisTemplate.opsForValue().set(taskCacheKey, taskVO, 300000, TimeUnit.SECONDS);

    }

    public SseEmitter startStreaming(String sessionId, String messageId) {
        String cacheKey = buildCacheKey(sessionId, messageId);
        ChatVO request = (ChatVO) redisTemplate.opsForValue().get(cacheKey);

        if (request == null) {
            throw new RuntimeException("Message request expired or not found");
        }

        // 从Redis获取userId
        String userIdKey = buildUserIdCacheKey(sessionId, messageId);
        String userId = (String) redisTemplate.opsForValue().get(userIdKey);
        if (userId == null) {
            userId = "anonymous"; // 默认用户ID
        }

        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);

        try {
            // 发送开始事件
            sendStartEvent(emitter, messageId);

            // 从数据库加载历史记录到上下文
            List<Map<String, String>> historyMessages = loadHistoryMessages(sessionId, messageId);
            log.info("加载历史记录，sessionId: {}, 历史消息数: {}", sessionId, historyMessages.size());

            // 生成AI任务计划
            String goal = request.getContent();
            log.info("开始生成任务计划，messageId: {}, goal: {}", messageId, goal);

            List<TaskStep> plan = aiStepsPlanning.generatePlan(goal, historyMessages);
            aiStepsPlanning.savePlanToRedis(messageId, plan);
            log.info("任务计划生成成功，messageId: {}, 计划步骤数：{}", messageId, plan.size());

            // 发送trace初始化事件（包含AI生成的计划步骤）
            sendTraceInitEvent(emitter, messageId, plan);

            // 执行计划并收集结果
            List<String> toolResults = executePlanAndCollectResults(emitter, messageId, plan);

            // 生成并发送最终结果，同时保存到数据库
            generateAndSendFinalResult(emitter, sessionId, messageId, goal, toolResults, request, userId);

            emitter.complete();

        } catch (Exception e) {
            log.error("Stream processing error", e);
            try {
                sendErrorEvent(emitter, messageId, "处理失败: " + e.getMessage());
            } catch (IOException ex) {
                log.warn("发送错误事件失败");
            }
            emitter.completeWithError(e);
            redisTemplate.delete(cacheKey);
        }

        return emitter;
    }

    private void sendStartEvent(SseEmitter emitter, String messageId) throws IOException {
        Map<String, Object> startData = new HashMap<>();
        startData.put("type", "start");
        startData.put("messageId", messageId);

        emitter.send(SseEmitter.event()
                .name("start")
                .data(new ObjectMapper().writeValueAsString(startData)));
    }

    private void sendTraceInitEvent(SseEmitter emitter, String messageId, List<TaskStep> plan) throws IOException {
        Map<String, Object> traceData = new HashMap<>();
        traceData.put("event", "trace");
        traceData.put("data", Map.of(
            "type", "trace_init",
            "messageId", messageId,
            "trace", buildTraceMap(plan, null, "processing")
        ));

        emitter.send(SseEmitter.event()
                .name("trace")
                .data(objectMapper.writeValueAsString(traceData)));
    }

    private Map<String, Object> buildTraceMap(List<TaskStep> plan, String activeStepId, String status) {
        Map<String, Object> trace = new HashMap<>();
        trace.put("status", status);
        trace.put("activeStepId", activeStepId);

        List<Map<String, Object>> steps = new ArrayList<>();
        for (TaskStep step : plan) {
            Map<String, Object> stepMap = new HashMap<>();
            stepMap.put("stepId", step.getStepId());
            stepMap.put("title", step.getTitle());
            stepMap.put("status", step.getStatus());
            stepMap.put("toolName", step.getToolName() != null ? step.getToolName() : "");
            stepMap.put("toolParams", step.getToolParams() != null ? step.getToolParams() : "");
            steps.add(stepMap);
        }
        trace.put("steps", steps);

        return trace;
    }

    private void sendTraceStepEvent(SseEmitter emitter, String messageId, List<TaskStep> plan, String activeStepId) throws IOException {
        Map<String, Object> traceData = new HashMap<>();
        traceData.put("event", "trace");
        traceData.put("data", Map.of(
            "type", "trace_step",
            "messageId", messageId,
            "trace", buildTraceMap(plan, activeStepId, "processing")
        ));

        emitter.send(SseEmitter.event()
                .name("trace")
                .data(objectMapper.writeValueAsString(traceData)));
    }

    private List<String> executePlanAndCollectResults(SseEmitter emitter, String messageId, List<TaskStep> plan) throws IOException, InterruptedException {
        List<String> toolResults = new ArrayList<>();
        String contextCacheKey = buildContextCacheKey(messageId);

        taskControlService.updateTaskControlStatus(messageId, "running", null);

        for (TaskStep step : plan) {
            String stepId = step.getStepId();

            checkTaskControl(emitter, messageId, stepId);

            // 发送trace步骤事件，标记当前执行的步骤
            sendTraceStepEvent(emitter, messageId, plan, stepId);

            String toolName = step.getToolName();
            String toolParams = step.getToolParams();

            if (toolName == null || toolName.isEmpty()) {
                log.info("步骤 {} 无工具调用，跳过", stepId);
                step.setStatus("completed");
                aiStepsPlanning.savePlanToRedis(messageId, plan);
                continue;
            }

            if ("match_and_recommend".equals(toolName)) {
                String approvalId = "apr_" + IdGenerator.generateShortId();

                sendApprovalEvent(emitter, messageId, stepId, toolName, toolParams, approvalId);

                saveApprovalToRedis(messageId, approvalId, stepId, toolName, toolParams);

                log.info("等待审批：messageId={}, approvalId={}", messageId, approvalId);

                String decision = waitForApproval(messageId, approvalId);

                if (!"approve".equals(decision)) {
                    log.info("审批未通过，跳过步骤 {}，decision: {}", stepId, decision);
                    step.setStatus("skipped");
                    aiStepsPlanning.savePlanToRedis(messageId, plan);
                    continue;
                }

                log.info("审批通过，执行步骤 {}", stepId);
            }

            sendToolCallEvent(emitter, messageId, stepId, toolName);

            String result = callTool(toolName, toolParams);

            saveToolResultToRedis(messageId, stepId, result, contextCacheKey);

            sendToolResultEvent(emitter, messageId, stepId, toolName, result);

            toolResults.add(result);

            step.setStatus("completed");
            taskControlService.updateTaskControlStatus(messageId, "running", stepId);
            aiStepsPlanning.savePlanToRedis(messageId, plan);
        }

        return toolResults;
    }

    private void checkTaskControl(SseEmitter emitter, String messageId, String stepId) throws IOException, InterruptedException {
        TaskControl control = taskControlService.getTaskControl(messageId);

        if (control == null) {
            return;
        }

        String status = control.getStatus();
        String action = control.getAction();

        if ("paused".equals(status)) {
            log.info("任务已暂停，等待恢复：messageId={}", messageId);

            sendTaskControlEvent(emitter, messageId, "paused", stepId);

            CountDownLatch latch = new CountDownLatch(1);
            redisMessageSubscriber.registerLatch(messageId, latch);

            boolean awaited = latch.await(300, TimeUnit.SECONDS);

            if (!awaited) {
                log.warn("等待恢复超时：messageId={}", messageId);
                throw new RuntimeException("任务暂停超时");
            }

            control = taskControlService.getTaskControl(messageId);
            if (control != null && "cancelled".equals(control.getStatus())) {
                log.info("任务已取消：messageId={}", messageId);
                throw new RuntimeException("任务已取消");
            }

            sendTaskControlEvent(emitter, messageId, "resumed", stepId);
            log.info("任务已恢复：messageId={}", messageId);
        } else if ("cancelled".equals(status)) {
            log.info("任务已取消：messageId={}", messageId);
            throw new RuntimeException("任务已取消");
        }
    }

    private void sendTaskControlEvent(SseEmitter emitter, String messageId, String action, String stepId) throws IOException {
        Map<String, Object> controlData = new HashMap<>();
        controlData.put("type", "task_control");
        controlData.put("messageId", messageId);
        controlData.put("action", action);
        controlData.put("stepId", stepId);

        emitter.send(SseEmitter.event()
                .name("task_control")
                .data(objectMapper.writeValueAsString(controlData)));
    }

    private String callTool(String toolName, String toolParams) {
        log.info("调用专家：{}, params: {}", toolName, toolParams);

        String result;
        try {
            switch (toolName) {
                case "profile_eval":
                    String userId = toolParams != null && !toolParams.isEmpty() ? toolParams : "111";
                    result = functionsCalling.profile_eval(userId);
                    break;
                case "match_and_recommend":
                    result = functionsCalling.match_and_recommend(toolParams);
                    break;
                case "route_planning":
                    result = functionsCalling.route_planning(toolParams);
                    break;
                case "report_custom":
                    result = functionsCalling.report_custom(toolParams);
                    break;
                default:
                    log.warn("未知专家：{}", toolName);
                    result = "专家 " + toolName + " 未实现";
            }
        } catch (Exception e) {
            log.error("专家调用失败：{}, error: {}", toolName, e.getMessage(), e);
            result = "专家调用失败: " + e.getMessage();
        }

        return result;
    }

    private void saveToolResultToRedis(String messageId, String stepId, String result, String contextCacheKey) {
        String resultKey = buildToolResultCacheKey(messageId, stepId);
        redisTemplate.opsForValue().set(resultKey, result, 300000, TimeUnit.SECONDS);

        redisTemplate.opsForList().rightPush(contextCacheKey, result);
        redisTemplate.expire(contextCacheKey, 300000, TimeUnit.SECONDS);

        log.info("工具结果已保存：stepId={}, result={}", stepId, result);
    }

    private void generateAndSendFinalResult(SseEmitter emitter, String sessionId, String messageId, String goal, List<String> toolResults, ChatVO request, String userId) throws IOException {
        log.info("开始生成最终结果");

        // 发送steps_completed事件
        sendStepsCompletedEvent(emitter, messageId);

        // 构建AI提示词
        String prompt = AI_agent_prompt.buildSystemPrompt(goal);

        // 调用AI流式接口生成结果
        StringBuilder fullContent = new StringBuilder();
        StringBuilder actionsContent = new StringBuilder();
        final boolean[] inActionsSection = {false};
        final boolean[] actionsCompleted = {false};

        // 用于存储steps信息
        final List<Map<String, Object>>[] stepsTrace = new List[]{new ArrayList<>()};

        Flux<String> aiResponse = streamingChatClient.prompt(prompt)
                .options(ChatOptions.builder().model("qwen-max").build())
                .stream()
                .content();

        // 使用CountDownLatch等待流完成
        CountDownLatch latch = new CountDownLatch(1);

        aiResponse.subscribe(
                chunk -> {
                    try {
                        log.info("chunk: {}", chunk);

                        // 检查是否进入ACTIONS区域
                        if (!inActionsSection[0]) {
                            if (chunk.contains("ACTIONS")) {
                                inActionsSection[0] = true;
                                // 分割内容，ACTIONS之前的部分继续流式输出
                                int actionsIndex = chunk.indexOf("ACTIONS");
                                if (actionsIndex > 0) {
                                    String beforeActions = chunk.substring(0, actionsIndex);
                                    if (!beforeActions.isEmpty()) {
                                        sendDeltaEvent(emitter, messageId, beforeActions, fullContent);
                                    }
                                }
                                actionsContent.append(chunk.substring(actionsIndex));
                            } else {
                                // 正常流式输出
                                sendDeltaEvent(emitter, messageId, chunk, fullContent);
                            }
                        } else {
                            // 已经在ACTIONS区域，只收集不输出
                            actionsContent.append(chunk);

                            // 检查ACTIONS是否结束（遇到END标记）
                            if (chunk.contains("END") && !actionsCompleted[0]) {
                                actionsCompleted[0] = true;
                                // 提取并保存actions
                                List<Map<String, String>> actions = extractActions(actionsContent.toString());
                                saveActionsToRedis(messageId, actions);
                                log.info("ACTIONS已识别并保存，actions数量: {}", actions.size());
                            }
                        }
                    } catch (IOException e) {
                        log.error("Send delta event error", e);
                        throw new RuntimeException("Connection closed");
                    } catch (IllegalStateException e) {
                        log.error("Emitter already completed", e);
                        throw new RuntimeException("Emitter completed");
                    }
                },
                error -> {
                    log.error("AI stream error: {}", error.getMessage());
                    try {
                        sendErrorEvent(emitter, messageId, "AI处理出错: " + error.getMessage());
                    } catch (IOException e) {
                        log.warn("Failed to send error event");
                    }
                    latch.countDown();
                },
                () -> {
                    log.info("AI stream completed");
                    try {
                        String finalResult = fullContent.toString();
                        saveFinalResultToRedis(messageId, finalResult);

                        // 从redis获取actions
                        List<Map<String, String>> actions = getActionsFromRedis(messageId);
                        sendDoneEventWithActions(emitter, messageId, finalResult, actions);

                        // 从Redis获取steps信息
                        stepsTrace[0] = getStepsFromRedis(messageId);

                        // 保存会话和消息到数据库
                        saveChatHistory(sessionId, messageId, request, finalResult, actions, stepsTrace[0], userId);
                    } catch (IOException e) {
                        log.warn("Failed to send done event");
                    }
                    latch.countDown();
                }
        );

        try {
            latch.await(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            log.error("等待AI流完成被中断", e);
            Thread.currentThread().interrupt();
        }

        log.info("任务完成，最终结果长度：{} 字符", fullContent.length());
    }

    /**
     * 从Redis获取steps信息
     */
    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> getStepsFromRedis(String messageId) {
        String planKey = String.format("ai:plan:%s", messageId);
        Object planObj = redisTemplate.opsForValue().get(planKey);

        if (planObj instanceof List) {
            List<?> planList = (List<?>) planObj;
            List<Map<String, Object>> steps = new ArrayList<>();

            for (Object item : planList) {
                if (item instanceof TaskStep) {
                    TaskStep step = (TaskStep) item;
                    Map<String, Object> stepMap = new HashMap<>();
                    stepMap.put("stepId", step.getStepId());
                    stepMap.put("title", step.getTitle());
                    stepMap.put("status", step.getStatus());
                    stepMap.put("toolName", step.getToolName() != null ? step.getToolName() : "");
                    stepMap.put("toolParams", step.getToolParams() != null ? step.getToolParams() : "");
                    steps.add(stepMap);
                }
            }
            return steps;
        }

        return new ArrayList<>();
    }

    /**
     * 保存会话和消息记录到数据库
     */
    private void saveChatHistory(String sessionId, String messageId, ChatVO request,
                                 String finalResult, List<Map<String, String>> actions,
                                 List<Map<String, Object>> steps, String userId) {
        try {
            // 1. 保存或更新会话信息
            saveOrUpdateSession(sessionId, request, finalResult, userId);

            // 2. 保存用户消息
            saveUserMessage(sessionId, messageId, request);

            // 3. 保存助手消息
            saveAssistantMessage(sessionId, messageId, finalResult, actions, steps);

            log.info("会话和消息记录已保存到数据库，sessionId: {}, messageId: {}", sessionId, messageId);
        } catch (Exception e) {
            log.error("保存会话和消息记录失败", e);
        }
    }

    /**
     * 保存或更新会话信息
     */
    private void saveOrUpdateSession(String sessionId, ChatVO request, String finalResult, String userId) {
        // 查询会话是否已存在
        ChatSessionPO existingSession = chatSessionMapper.selectOne(
                new com.baomidou.mybatisplus.core.conditions.query.QueryWrapper<ChatSessionPO>()
                        .eq("session_id", sessionId)
        );

        LocalDateTime now = LocalDateTime.now();

        if (existingSession == null) {
            // 创建新会话
            ChatSessionPO session = ChatSessionPO.builder()
                    .sessionId(sessionId)
                    .userId(userId)
                    .title(generateSessionTitle(request.getContent()))
                    .lastMessagePreview(finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult)
                    .pinned(false)
                    .favorited(false)
                    .updatedAt(now)
                    .build();
            chatSessionMapper.insert(session);
        } else {
            // 更新现有会话
            existingSession.setLastMessagePreview(finalResult.length() > 100 ? finalResult.substring(0, 100) + "..." : finalResult);
            existingSession.setUpdatedAt(now);
            chatSessionMapper.updateById(existingSession);
        }
    }

    /**
     * 生成会话标题
     */
    private String generateSessionTitle(String content) {
        if (content == null || content.isEmpty()) {
            return "新对话";
        }
        // 取前20个字符作为标题
        return content.length() > 20 ? content.substring(0, 20) + "..." : content;
    }

    /**
     * 保存用户消息
     */
    private void saveUserMessage(String sessionId, String messageId, ChatVO request) {
        ChatMessagePO userMessage = ChatMessagePO.builder()
                .messageId(messageId + "_user")
                .sessionId(sessionId)
                .role("user")
                .content(request.getContent())
                .status("succeeded")
                .createdAt(LocalDateTime.now())
                .build();
        chatMessageMapper.insert(userMessage);
    }

    /**
     * 保存助手消息
     */
    private void saveAssistantMessage(String sessionId, String messageId, String finalResult,
                                      List<Map<String, String>> actions, List<Map<String, Object>> steps) {
        // 构建agentTrace
        Map<String, Object> agentTrace = new HashMap<>();
        agentTrace.put("steps", steps);

        // 转换actions格式
        List<Map<String, Object>> actionsList = new ArrayList<>();
        if (actions != null) {
            for (Map<String, String> action : actions) {
                Map<String, Object> actionMap = new HashMap<>(action);
                actionsList.add(actionMap);
            }
        }

        ChatMessagePO assistantMessage = ChatMessagePO.builder()
                .messageId(messageId + "_assistant")
                .sessionId(sessionId)
                .role("assistant")
                .content(finalResult)
                .status("succeeded")
                .actions(actionsList)
                .agentTrace(agentTrace)
                .createdAt(LocalDateTime.now())
                .build();
        chatMessageMapper.insert(assistantMessage);
    }

    private void saveActionsToRedis(String messageId, List<Map<String, String>> actions) {
        String actionsKey = buildActionsCacheKey(messageId);
        redisTemplate.opsForValue().set(actionsKey, actions, 300000, TimeUnit.SECONDS);
        log.info("Actions已保存到Redis: {}, actions数量: {}", actionsKey, actions.size());
    }

    private List<Map<String, String>> getActionsFromRedis(String messageId) {
        String actionsKey = buildActionsCacheKey(messageId);
        Object obj = redisTemplate.opsForValue().get(actionsKey);
        if (obj instanceof List) {
            return (List<Map<String, String>>) obj;
        }
        return new ArrayList<>();
    }

    private String buildActionsCacheKey(String messageId) {
        return String.format("assistant:actions:%s", messageId);
    }

    private void sendDoneEventWithActions(SseEmitter emitter, String messageId, String fullContent, List<Map<String, String>> actions) throws IOException {
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("type", "done");

        Map<String, Object> message = new HashMap<>();
        message.put("messageId", messageId);
        message.put("role", "assistant");
        message.put("content", fullContent);
        message.put("status", "succeeded");
        message.put("createdAt", Instant.now().toString());
        message.put("actions", actions != null ? actions : new ArrayList<>());

        Map<String, Object> agentTrace = new HashMap<>();
        agentTrace.put("status", "succeeded");
        agentTrace.put("activeStepId", null);
        message.put("agentTrace", agentTrace);

        doneData.put("message", message);

        emitter.send(SseEmitter.event()
                .name("done")
                .data(objectMapper.writeValueAsString(doneData)));
    }

    private void sendStepsCompletedEvent(SseEmitter emitter, String messageId) throws IOException {
        Map<String, Object> eventData = new HashMap<>();
        eventData.put("messageId", messageId);

        emitter.send(SseEmitter.event()
                .name("steps_completed")
                .data(objectMapper.writeValueAsString(eventData)));
    }

    private void sendDeltaEvent(SseEmitter emitter, String messageId, String delta, StringBuilder fullContent) throws IOException {
        fullContent.append(delta);

        Map<String, Object> deltaData = new HashMap<>();
        deltaData.put("type", "delta");
        deltaData.put("delta", delta);
        deltaData.put("content", fullContent.toString());

        emitter.send(SseEmitter.event()
                .name("delta")
                .data(objectMapper.writeValueAsString(deltaData)));
    }

    private void sendDoneEvent(SseEmitter emitter, String messageId, String fullContent) throws IOException {
        Map<String, Object> doneData = new HashMap<>();
        doneData.put("type", "done");

        Map<String, Object> message = new HashMap<>();
        message.put("messageId", messageId);
        message.put("role", "assistant");
        message.put("content", fullContent);
        message.put("status", "succeeded");
        message.put("createdAt", Instant.now().toString());
        message.put("actions", new ArrayList<>());

        Map<String, Object> agentTrace = new HashMap<>();
        agentTrace.put("status", "succeeded");
        agentTrace.put("activeStepId", null);
        message.put("agentTrace", agentTrace);

        doneData.put("message", message);

        emitter.send(SseEmitter.event()
                .name("done")
                .data(objectMapper.writeValueAsString(doneData)));
    }

    private void saveFinalResultToRedis(String messageId, String result) {
        String resultKey = buildFinalResultCacheKey(messageId);
        redisTemplate.opsForValue().set(resultKey, result, 300000, TimeUnit.SECONDS);
        log.info("最终结果已保存到 Redis: {}", resultKey);
    }

    private void sendToolCallEvent(SseEmitter emitter, String messageId, String stepId, String toolName) throws IOException {
        ToolCallEvent.ToolInfo toolInfo = ToolCallEvent.ToolInfo.builder()
                .name(toolName)
                .requiresApproval(false)
                .build();

        ToolCallEvent event = ToolCallEvent.builder()
                .taskId(messageId)
                .stepId(stepId)
                .tool(toolInfo)
                .build();

        emitter.send(SseEmitter.event()
                .name("tool_call")
                .data(objectMapper.writeValueAsString(event)));
    }

    private void sendToolResultEvent(SseEmitter emitter, String messageId, String stepId, String toolName, String result) throws IOException {
        String resultSummary = extractSummary(result);

        ToolResultEvent.ToolResultInfo toolResultInfo = ToolResultEvent.ToolResultInfo.builder()
                .name(toolName)
                .resultSummary(resultSummary)
                .build();

        ToolResultEvent event = ToolResultEvent.builder()
                .taskId(messageId)
                .stepId(stepId)
                .tool(toolResultInfo)
                .build();

        emitter.send(SseEmitter.event()
                .name("tool_result")
                .data(objectMapper.writeValueAsString(event)));
    }

    private String extractSummary(String result) {
        if (result == null || result.isEmpty()) {
            return "无结果";
        }

        if (result.length() <= 50) {
            return result;
        }

        return result.substring(0, 50) + "...";
    }

    private void sendApprovalEvent(SseEmitter emitter, String messageId, String stepId, String toolName, String toolParams, String approvalId) throws IOException {
        ApprovalEvent.ApprovalToolInfo toolInfo = ApprovalEvent.ApprovalToolInfo.builder()
                .name(toolName)
                .params(toolParams)
                .approvalId(approvalId)
                .build();

        ApprovalEvent event = ApprovalEvent.builder()
                .taskId(messageId)
                .stepId(stepId)
                .tool(toolInfo)
                .build();

        emitter.send(SseEmitter.event()
                .name("approval_required")
                .data(objectMapper.writeValueAsString(event)));
    }

    private void saveApprovalToRedis(String messageId, String approvalId, String stepId, String toolName, String toolParams) {
        String approvalKey = buildApprovalCacheKey(approvalId);

        Map<String, Object> approvalData = new HashMap<>();
        approvalData.put("taskId", messageId);
        approvalData.put("approvalId", approvalId);
        approvalData.put("stepId", stepId);
        approvalData.put("toolName", toolName);
        approvalData.put("toolParams", toolParams);
        approvalData.put("status", "pending");
        approvalData.put("decision", null);
        approvalData.put("createdAt", LocalDateTime.now());

        redisTemplate.opsForValue().set(approvalKey, approvalData, 300000, TimeUnit.SECONDS);
        log.info("审批信息已保存：approvalId={}", approvalId);
    }

    private String waitForApproval(String messageId, String approvalId) throws InterruptedException {
        log.info("等待审批结果：messageId={}, approvalId={}", messageId, approvalId);

        CountDownLatch latch = new CountDownLatch(1);
        redisMessageSubscriber.registerLatch(approvalId, latch);
        log.info("已注册 latch 等待审批：approvalId={}, latchMap 当前大小={}", approvalId, latch.hashCode());

        log.info("开始阻塞等待审批结果...");
        boolean awaited = latch.await(300, TimeUnit.SECONDS);
        log.info("latch.await() 返回：awaited={}, approvalId={}", awaited, approvalId);

        if (!awaited) {
            log.warn("审批超时：approvalId={}", approvalId);
            return "timeout";
        }

        String decision = redisMessageSubscriber.getApprovalResult(approvalId);
        log.info("从 approvalResultMap 获取决策：approvalId={}, decision={}", approvalId, decision);

        if (decision != null) {
            String approvalKey = buildApprovalCacheKey(approvalId);
            Map<String, Object> approvalData = (Map<String, Object>) redisTemplate.opsForValue().get(approvalKey);

            if (approvalData != null) {
                approvalData.put("status", "completed");
                redisTemplate.opsForValue().set(approvalKey, approvalData, 300000, TimeUnit.SECONDS);
            }

            log.info("审批结果已获取：approvalId={}, decision={}", approvalId, decision);
            return decision;
        }

        log.warn("未获取到审批结果：approvalId={}", approvalId);
        return "timeout";
    }

    public ApprovalResponse processApproval(String messageId, ApprovalRequest request) {
        String approvalKey = buildApprovalCacheKey(request.getApprovalId());

        Map<String, Object> approvalData = (Map<String, Object>) redisTemplate.opsForValue().get(approvalKey);

        if (approvalData == null) {
            throw new RuntimeException("审批记录不存在或已过期");
        }

        String storedMessageId = (String) approvalData.get("taskId");
        if (!messageId.equals(storedMessageId)) {
            throw new RuntimeException("messageId 不匹配");
        }

        approvalData.put("decision", request.getDecision());
        approvalData.put("comment", request.getComment());
        approvalData.put("processedAt", LocalDateTime.now());

        redisTemplate.opsForValue().set(approvalKey, approvalData, 300000, TimeUnit.SECONDS);

        String approvalChannel = "approval:" + request.getApprovalId();
        stringRedisTemplate.convertAndSend(approvalChannel, request.getDecision());
        log.info("已发布审批结果：approvalId={}, decision={}", request.getApprovalId(), request.getDecision());

        String status = "approve".equals(request.getDecision()) ? "running" : "rejected";

        return ApprovalResponse.builder()
                .taskId(messageId)
                .approvalId(request.getApprovalId())
                .decision(request.getDecision())
                .status(status)
                .build();
    }

    private void sendErrorEvent(SseEmitter emitter, String messageId, String error) throws IOException {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("type", "error");
        errorData.put("messageId", messageId);
        errorData.put("error", error);

        emitter.send(SseEmitter.event()
                .name("error")
                .data(objectMapper.writeValueAsString(errorData)));
    }

    public TaskArtifactsResponse getTaskArtifacts(String messageId) {
        String finalResultKey = buildFinalResultCacheKey(messageId);
        String finalResult = (String) redisTemplate.opsForValue().get(finalResultKey);

        List<TaskArtifactsResponse.ArtifactItem> artifacts = new ArrayList<>();

        if (finalResult != null && !finalResult.isEmpty()) {
            TaskArtifactsResponse.ArtifactItem artifact = TaskArtifactsResponse.ArtifactItem.builder()
                    .artifactId("a1")
                    .type("plan_markdown")
                    .title("3 个月求职冲刺计划")
                    .content(finalResult)
                    .createdAt(LocalDateTime.now())
                    .build();
            artifacts.add(artifact);
        }

        return TaskArtifactsResponse.builder()
                .taskId(messageId)
                .total(artifacts.size())
                .list(artifacts)
                .build();
    }

    public void controlTask(String messageId, String action) {
        taskControlService.controlTask(messageId, action);
    }

    private List<Map<String, String>> loadHistoryMessages(String sessionId, String currentMessageId) {
        List<Map<String, String>> historyMessages = new ArrayList<>();

        try {
            // 从数据库查询该会话的所有消息
            List<ChatMessagePO> messages = chatMessageMapper.selectBySessionId(sessionId);

            if (messages == null || messages.isEmpty()) {
                log.info("该会话没有历史消息，sessionId: {}", sessionId);
                return historyMessages;
            }

            // 过滤掉当前消息，只保留历史消息
            for (ChatMessagePO message : messages) {
                // 跳过当前正在处理的消息
                if (currentMessageId.equals(message.getMessageId())) {
                    continue;
                }

                // 只添加用户和助手的消息
                String role = message.getRole();
                if ("user".equals(role) || "assistant".equals(role)) {
                    Map<String, String> msg = new HashMap<>();
                    msg.put("role", role);
                    msg.put("content", message.getContent());
                    historyMessages.add(msg);
                }
            }

            log.info("成功加载历史消息，sessionId: {}, 历史消息数: {}", sessionId, historyMessages.size());
        } catch (Exception e) {
            log.error("加载历史消息失败，sessionId: {}", sessionId, e);
        }

        return historyMessages;
    }

    private String buildCacheKey(String sessionId, String messageId) {
        return String.format("assistant:request:%s:%s", sessionId, messageId);
    }

    private String buildTaskCacheKey(String sessionId) {
        return String.format("assistant:task:%s", sessionId);
    }

    private String buildContextCacheKey(String messageId) {
        return String.format("assistant:context:%s", messageId);
    }

    private String buildToolResultCacheKey(String messageId, String stepId) {
        return String.format("assistant:tool_result:%s:%s", messageId, stepId);
    }

    private String buildFinalResultCacheKey(String messageId) {
        return String.format("assistant:final_result:%s", messageId);
    }

    private String buildApprovalCacheKey(String approvalId) {
        return String.format("assistant:approval:%s", approvalId);
    }

    private String buildUserIdCacheKey(String sessionId, String messageId) {
        return String.format("assistant:userId:%s:%s", sessionId, messageId);
    }

    private List<Map<String, String>> extractActions(String content) {
        List<Map<String, String>> actions = new ArrayList<>();

        // 匹配ACTIONS {...} END格式
        Pattern pattern = Pattern.compile("ACTIONS\\s*\\{([\\s\\S]*?)\\}\\s*END");
        Matcher matcher = pattern.matcher(content);

        if (matcher.find()) {
            String actionsStr = matcher.group(1);
            // 匹配每个独立的大括号内的action对象
            Pattern actionPattern = Pattern.compile("\\{([^\\{\\}]+)\\}");
            Matcher actionMatcher = actionPattern.matcher(actionsStr);

            while (actionMatcher.find()) {
                Map<String, String> action = new HashMap<>();
                String actionContent = actionMatcher.group(1);

                // 匹配键值对："key": "value"
                Pattern kvPattern = Pattern.compile("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"");
                Matcher kvMatcher = kvPattern.matcher(actionContent);

                while (kvMatcher.find()) {
                    action.put(kvMatcher.group(1), kvMatcher.group(2));
                }

                if (!action.isEmpty()) {
                    actions.add(action);
                }
            }
        }

        return actions;
    }
}