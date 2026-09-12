package group.resumeparserservice.service;/* I love coding */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.resumeparserservice.config.RedisMessageSubscriber;
import group.resumeparserservice.domain.vo.*;
import group.resumeparserservice.prompts.AI_agent_prompt;
import group.resumeparserservice.service.AI_comprehend;
import group.resumeparserservice.service.AI_steps_planning;
import group.resumeparserservice.service.FunctionsCalling;
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
public class AgentService {
    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private ChatClient streamingChatClient;

    @Autowired
    private AI_steps_planning aiStepsPlanning;

    @Autowired
    private FunctionsCalling functionsCalling;

    @Autowired
    private AI_comprehend aiComprehend;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskControlService taskControlService;

    @Autowired
    private RedisMessageSubscriber redisMessageSubscriber;

    public TaskResponse createAgentTask(AgentTaskVO taskVO) {
        String taskId = "hat_" + IdGenerator.generateShortId();
        String cacheKey = buildTaskCacheKey(taskId);

        redisTemplate.opsForValue().set(cacheKey, taskVO, 300000, TimeUnit.SECONDS);

        String urlTemplate = "/users/me/home/assistant/tasks/%s/stream";
        String url = String.format(urlTemplate, taskId);

        StreamConfig streamConfig = StreamConfig.builder()
                .protocol("sse")
                .url(url)
                .build();

        TaskResponse response = TaskResponse.builder()
                .taskId(taskId)
                .status("planning")
                .createdAt(LocalDateTime.now())
                .stream(streamConfig)
                .pollAfterMs(1200L)
                .build();

        return response;
    }

    private String buildContextString(AgentTaskVO.TaskContext context) {
        if (context == null) {
            return null;
        }
        try {
            Map<String, Object> contextMap = new HashMap<>();
            contextMap.put("routePath", context.getRoutePath());
            contextMap.put("pageTitle", context.getPageTitle());
            if (context.getData() != null) {
                contextMap.putAll(context.getData());
            }
            return objectMapper.writeValueAsString(contextMap);
        } catch (Exception e) {
            log.warn("序列化上下文失败", e);
            return context.toString();
        }
    }

//    public SseEmitter startTaskStreaming(String taskId) {
//        String cacheKey = buildTaskCacheKey(taskId);
//        AgentTaskVO taskVO = (AgentTaskVO) redisTemplate.opsForValue().get(cacheKey);
//
//        if (taskVO == null) {
//            throw new RuntimeException("Task not found or expired");
//        }
//
//        SseEmitter emitter = new SseEmitter(5 * 60 * 1000L);
//
//        try {
//            sendTaskStartEvent(emitter, taskId);
//
//            String goal = taskVO.getGoal();
//            String context = buildContextString(taskVO.getContext());
//
//            log.info("开始生成任务计划，goal: {}, context: {}", goal, context);
//
//            List<TaskStep> plan = aiStepsPlanning.generatePlan(goal, context);
//
//            aiStepsPlanning.savePlanToRedis(taskId, plan);
//
//            log.info("任务计划生成成功，taskId: {}, 计划步骤数：{}", taskId, plan.size());
//
//            sendPlanGeneratedEvent(emitter, taskId, plan);
//
//            List<String> toolResults = executePlanAndCollectResults(emitter, taskId, plan);
//
//            generateAndSendFinalResult(emitter, taskId, goal, toolResults);
//
//            emitter.complete();
//
//        } catch (Exception e) {
//            log.error("Task stream processing error", e);
//            try {
//                sendErrorEvent(emitter, taskId, "处理失败：" + e.getMessage());
//            } catch (IOException ex) {
//                log.warn("发送错误事件失败");
//            }
//            emitter.completeWithError(e);
//            redisTemplate.delete(cacheKey);
//        }
//
//        return emitter;
//    }

//    private List<String> executePlanAndCollectResults(SseEmitter emitter, String taskId, List<TaskStep> plan) throws IOException, InterruptedException {
//        List<String> toolResults = new ArrayList<>();
//        String contextCacheKey = buildContextCacheKey(taskId);
//
//        taskControlService.updateTaskControlStatus(taskId, "running", null);
//
//        for (TaskStep step : plan) {
//            String stepId = step.getStepId();
//
//            checkTaskControl(emitter, taskId, stepId);
//
//            String toolName = step.getToolName();
//            String toolParams = step.getToolParams();
//
//            if (toolName == null || toolName.isEmpty()) {
//                log.info("步骤 {} 无工具调用，跳过", stepId);
//                continue;
//            }
//
//            if ("recommend_with_filters".equals(toolName)) {
//                String approvalId = "apr_" + IdGenerator.generateShortId();
//
//                sendApprovalEvent(emitter, taskId, stepId, toolName, approvalId);
//
//                saveApprovalToRedis(taskId, approvalId, stepId, toolName, toolParams);
//
//                log.info("等待审批：taskId={}, approvalId={}", taskId, approvalId);
//
//                String decision = waitForApproval(taskId, approvalId);
//
//                if (!"approve".equals(decision)) {
//                    log.info("审批未通过，跳过步骤 {}，decision: {}", stepId, decision);
//                    step.setStatus("skipped");
//                    aiStepsPlanning.savePlanToRedis(taskId, plan);
//                    continue;
//                }
//
//                log.info("审批通过，执行步骤 {}", stepId);
//            }
//
//            sendToolCallEvent(emitter, taskId, stepId, toolName);
//
//            String result = callTool(toolName, toolParams);
//
//            saveToolResultToRedis(taskId, stepId, result, contextCacheKey);
//
//            sendToolResultEvent(emitter, taskId, stepId, toolName, result);
//
//            toolResults.add(result);
//
//            step.setStatus("completed");
//            taskControlService.updateTaskControlStatus(taskId, "running", stepId);
//            aiStepsPlanning.savePlanToRedis(taskId, plan);
//        }
//
//        return toolResults;
//    }
//
//    private String callTool(String toolName, String toolParams) {
//        log.info("调用工具：{}, params: {}", toolName, toolParams);
//
//        String result;
//        switch (toolName) {
//            case "get_profile":
//                String userId = "111";
//                result = functionsCalling.getProfile(userId);
//                break;
//            case "recommend_with_filters":
//                result = functionsCalling.recommendWithFilters(toolParams);
//                break;
//            default:
//                log.warn("未知工具：{}", toolName);
//                result = "工具 " + toolName + " 未实现";
//        }
//
//        return result;
//    }

    private String extractUserId(String toolParams) {
        if (toolParams == null || toolParams.isEmpty()) {
            return "111";
        }
        try {
            Map<String, Object> params = objectMapper.readValue(toolParams, Map.class);
            return (String) params.getOrDefault("userId", "111");
        } catch (Exception e) {
            log.warn("解析 toolParams 失败", e);
            return "111";
        }
    }

    private void saveToolResultToRedis(String taskId, String stepId, String result, String contextCacheKey) {
        String resultKey = buildToolResultCacheKey(taskId, stepId);
        redisTemplate.opsForValue().set(resultKey, result, 300000, TimeUnit.SECONDS);

        redisTemplate.opsForList().rightPush(contextCacheKey, result);
        redisTemplate.expire(contextCacheKey, 300000, TimeUnit.SECONDS);

        log.info("工具结果已保存：stepId={}, result={}", stepId, result);
    }

    private void generateAndSendFinalResult(SseEmitter emitter, String taskId, String goal, List<String> toolResults) throws IOException {
        log.info("开始生成最终结果");

        String finalResult = aiComprehend.generateFinalResult(goal, toolResults);

        saveFinalResultToRedis(taskId, finalResult);

        sendTaskCompletedEvent(emitter, taskId);

        log.info("任务完成，最终结果长度：{} 字符", finalResult != null ? finalResult.length() : 0);
    }

    private void saveFinalResultToRedis(String taskId, String result) {
        String resultKey = buildFinalResultCacheKey(taskId);
        redisTemplate.opsForValue().set(resultKey, result, 300000, TimeUnit.SECONDS);
        log.info("最终结果已保存到 Redis: {}", resultKey);
    }

    private void sendTaskStartEvent(SseEmitter emitter, String taskId) throws IOException {
        Map<String, Object> startData = new HashMap<>();
        startData.put("type", "start");
        startData.put("taskId", taskId);

        emitter.send(SseEmitter.event()
                .name("start")
                .data(new ObjectMapper().writeValueAsString(startData)));
    }

    private void sendPlanGeneratedEvent(SseEmitter emitter, String taskId, List<TaskStep> plan) throws IOException {
        Map<String, Object> planData = new HashMap<>();
        planData.put("taskId", taskId);
        
        Map<String, Object> planVO = new HashMap<>();
        planVO.put("version", 1);
        planVO.put("steps", plan);
        
        planData.put("plan", planVO);

        emitter.send(SseEmitter.event()
                .name("plan_created")
                .data(new ObjectMapper().writeValueAsString(planData)));
    }

    private void sendToolCallEvent(SseEmitter emitter, String taskId, String stepId, String toolName) throws IOException {
        ToolCallEvent.ToolInfo toolInfo = ToolCallEvent.ToolInfo.builder()
                .name(toolName)
                .requiresApproval(false)
                .build();

        ToolCallEvent event = ToolCallEvent.builder()
                .taskId(taskId)
                .stepId(stepId)
                .tool(toolInfo)
                .build();

        emitter.send(SseEmitter.event()
                .name("tool_call")
                .data(new ObjectMapper().writeValueAsString(event)));
    }

    private void sendToolResultEvent(SseEmitter emitter, String taskId, String stepId, String toolName, String result) throws IOException {
        String resultSummary = extractSummary(result);

        ToolResultEvent.ToolResultInfo toolResultInfo = ToolResultEvent.ToolResultInfo.builder()
                .name(toolName)
                .resultSummary(resultSummary)
                .build();

        ToolResultEvent event = ToolResultEvent.builder()
                .taskId(taskId)
                .stepId(stepId)
                .tool(toolResultInfo)
                .build();

        emitter.send(SseEmitter.event()
                .name("tool_result")
                .data(new ObjectMapper().writeValueAsString(event)));
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

    private void sendTaskCompletedEvent(SseEmitter emitter, String taskId) throws IOException {
        TaskCompletedEvent.Artifact artifact = TaskCompletedEvent.Artifact.builder()
                .artifactId("a1")
                .type("plan_markdown")
                .build();

        TaskCompletedEvent.TaskResult taskResult = TaskCompletedEvent.TaskResult.builder()
                .summary("任务完成")
                .artifacts(List.of(artifact))
                .build();

        TaskCompletedEvent event = TaskCompletedEvent.builder()
                .taskId(taskId)
                .result(taskResult)
                .build();

        emitter.send(SseEmitter.event()
                .name("task_completed")
                .data(new ObjectMapper().writeValueAsString(event)));
    }

    private void sendErrorEvent(SseEmitter emitter, String taskId, String error) throws IOException {
        Map<String, Object> errorData = new HashMap<>();
        errorData.put("type", "error");
        errorData.put("taskId", taskId);
        errorData.put("error", error);

        emitter.send(SseEmitter.event()
                .name("error")
                .data(new ObjectMapper().writeValueAsString(errorData)));
    }

    private String buildTaskCacheKey(String taskId) {
        return String.format("assistant:task:%s", taskId);
    }

    private String buildContextCacheKey(String taskId) {
        return String.format("assistant:context:%s", taskId);
    }

    private String buildToolResultCacheKey(String taskId, String stepId) {
        return String.format("assistant:tool_result:%s:%s", taskId, stepId);
    }

    private String buildFinalResultCacheKey(String taskId) {
        return String.format("assistant:final_result:%s", taskId);
    }

    public TaskArtifactsResponse getTaskArtifacts(String taskId) {
        String finalResultKey = buildFinalResultCacheKey(taskId);
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
                .taskId(taskId)
                .total(artifacts.size())
                .list(artifacts)
                .build();
    }

    private void sendApprovalEvent(SseEmitter emitter, String taskId, String stepId, String toolName, String approvalId) throws IOException {
        ApprovalEvent.ApprovalToolInfo toolInfo = ApprovalEvent.ApprovalToolInfo.builder()
                .name(toolName)
                .approvalId(approvalId)
                .build();

        ApprovalEvent event = ApprovalEvent.builder()
                .taskId(taskId)
                .stepId(stepId)
                .tool(toolInfo)
                .build();

        emitter.send(SseEmitter.event()
                .name("approval_received")
                .data(new ObjectMapper().writeValueAsString(event)));
    }

    private void saveApprovalToRedis(String taskId, String approvalId, String stepId, String toolName, String toolParams) {
        String approvalKey = buildApprovalCacheKey(approvalId);
        
        Map<String, Object> approvalData = new HashMap<>();
        approvalData.put("taskId", taskId);
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

    private String waitForApproval(String taskId, String approvalId) throws InterruptedException {
        log.info("等待审批结果：taskId={}, approvalId={}", taskId, approvalId);
        
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

    public ApprovalResponse processApproval(String taskId, ApprovalRequest request) {
        String approvalKey = buildApprovalCacheKey(request.getApprovalId());
        
        Map<String, Object> approvalData = (Map<String, Object>) redisTemplate.opsForValue().get(approvalKey);
        
        if (approvalData == null) {
            throw new RuntimeException("审批记录不存在或已过期");
        }
        
        String storedTaskId = (String) approvalData.get("taskId");
        if (!taskId.equals(storedTaskId)) {
            throw new RuntimeException("taskId 不匹配");
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
                .taskId(taskId)
                .approvalId(request.getApprovalId())
                .decision(request.getDecision())
                .status(status)
                .build();
    }

    private String buildApprovalCacheKey(String approvalId) {
        return String.format("assistant:approval:%s", approvalId);
    }

    private void checkTaskControl(SseEmitter emitter, String taskId, String stepId) throws IOException, InterruptedException {
        TaskControl control = taskControlService.getTaskControl(taskId);
        
        if (control == null) {
            return;
        }
        
        String status = control.getStatus();
        String action = control.getAction();
        
        log.info("检查任务控制状态：taskId={}, status={}, action={}, stepId={}", taskId, status, action, stepId);
        
        if ("paused".equals(status) || "pause".equals(action)) {
            log.info("任务已暂停，等待恢复：taskId={}, stepId={}", taskId, stepId);
            
            sendControlEvent(emitter, taskId, "paused", stepId);
            
            CountDownLatch latch = new CountDownLatch(1);
            redisMessageSubscriber.registerLatch(taskId, latch);
            
            latch.await();
            
            log.info("任务恢复执行：taskId={}, stepId={}", taskId, stepId);
            sendControlEvent(emitter, taskId, "resumed", stepId);
            
            taskControlService.updateTaskControlStatus(taskId, "running", stepId);
        }
        
        if ("cancelled".equals(status) || "cancel".equals(action)) {
            log.info("任务已取消，停止执行：taskId={}, stepId={}", taskId, stepId);
            
            sendControlEvent(emitter, taskId, "cancelled", stepId);
            
            throw new TaskCancelledException("任务已被取消");
        }
        
        if ("retry".equals(action)) {
            log.info("任务重试：taskId={}, stepId={}", taskId, stepId);
            
            sendControlEvent(emitter, taskId, "retrying", stepId);
            
            control.setAction(null);
            taskControlService.saveTaskControl(taskId, control);
        }
    }

    private void sendControlEvent(SseEmitter emitter, String taskId, String action, String stepId) throws IOException {
        TaskControlEvent event = TaskControlEvent.builder()
                .taskId(taskId)
                .action(action)
                .status(action)
                .build();
        
        emitter.send(SseEmitter.event()
                .name("task_control")
                .data(objectMapper.writeValueAsString(event)));
        
        log.info("已发送控制事件：taskId={}, action={}", taskId, action);
    }

    public static class TaskCancelledException extends RuntimeException {
        public TaskCancelledException(String message) {
            super(message);
        }
    }

    public void controlTask(String taskId, String action) {
        log.info("任务控制：taskId={}, action={}", taskId, action);
        taskControlService.controlTask(taskId, action);
    }
}
