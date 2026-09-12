package group.resumeparserservice.service;/* I love coding */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.resumeparserservice.domain.vo.AgentTaskVO;
import group.resumeparserservice.domain.vo.PlanVO;
import group.resumeparserservice.domain.vo.TaskQueryResponse;
import group.resumeparserservice.domain.vo.TaskStep;
import group.resumeparserservice.prompts.AI_steps_planning_prompt;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AI_steps_planning {

    @Autowired
    private ChatClient chatClient;

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    public List<TaskStep> generatePlan(String goal, String context) {
        log.info("开始生成任务计划，goal: {}", goal);

        String prompt = AI_steps_planning_prompt.buildSystemPrompt(goal, context);

        String aiResponse = chatClient.prompt(prompt)
                .options(ChatOptions.builder().model("qwen-plus-2025-09-11").build())
                .call()
                .content();

        log.info("AI 生成的计划：{}", aiResponse);

        List<TaskStep> plan = parsePlan(aiResponse);

        return plan;
    }

    public List<TaskStep> generatePlan(String goal, List<Map<String, String>> historyMessages) {
        log.info("开始生成任务计划，goal: {}, 历史消息数: {}", goal, historyMessages != null ? historyMessages.size() : 0);

        // 将历史消息转换为上下文字符串
        String context = buildContextFromHistory(historyMessages);

        String prompt = AI_steps_planning_prompt.buildSystemPrompt(goal, context);

        String aiResponse = chatClient.prompt(prompt)
                .options(ChatOptions.builder().model("qwen-plus-2025-09-11").build())
                .call()
                .content();

        log.info("AI 生成的计划：{}", aiResponse);

        List<TaskStep> plan = parsePlan(aiResponse);

        return plan;
    }

    private String buildContextFromHistory(List<Map<String, String>> historyMessages) {
        if (historyMessages == null || historyMessages.isEmpty()) {
            return null;
        }

        StringBuilder context = new StringBuilder();
        context.append("以下是历史对话记录：\n\n");

        for (Map<String, String> message : historyMessages) {
            String role = message.get("role");
            String content = message.get("content");

            if ("user".equals(role)) {
                context.append("用户：").append(content).append("\n");
            } else if ("assistant".equals(role)) {
                context.append("助手：").append(content).append("\n");
            }
        }

        context.append("\n请基于以上历史对话上下文来理解当前用户的需求。");

        return context.toString();
    }

    private List<TaskStep> parsePlan(String jsonStr) {
        try {
            jsonStr = jsonStr.trim();
            if (jsonStr.startsWith("```json")) {
                jsonStr = jsonStr.substring(7);
            }
            if (jsonStr.endsWith("```")) {
                jsonStr = jsonStr.substring(0, jsonStr.length() - 3);
            }
            jsonStr = jsonStr.trim();

            List<TaskStep> steps = objectMapper.readValue(jsonStr, new TypeReference<List<TaskStep>>() {});

            for (TaskStep step : steps) {
                if (step.getStepId() == null || step.getStepId().isEmpty()) {
                    step.setStepId("s" + System.nanoTime());
                }
                if (step.getStatus() == null || step.getStatus().isEmpty()) {
                    step.setStatus("pending");
                }
            }

            log.info("计划解析成功，共{}个步骤", steps.size());
            return steps;

        } catch (JsonProcessingException e) {
            log.error("解析计划 JSON 失败：{}", jsonStr, e);
            throw new RuntimeException("解析任务计划失败：" + e.getMessage(), e);
        }
    }

    public void savePlanToRedis(String taskId, List<TaskStep> plan) {
        String cacheKey = buildPlanCacheKey(taskId);
        redisTemplate.opsForValue().set(cacheKey, plan, 300000, TimeUnit.SECONDS);
        log.info("任务计划已保存到 Redis，key: {}, 步骤数：{}", cacheKey, plan.size());
    }

    public List<TaskStep> getPlanFromRedis(String taskId) {
        String cacheKey = buildPlanCacheKey(taskId);
        Object obj = redisTemplate.opsForValue().get(cacheKey);

        if (obj == null) {
            log.warn("任务计划未找到或已过期，taskId: {}", taskId);
            return null;
        }

        if (obj instanceof List) {
            return (List<TaskStep>) obj;
        }

        log.warn("Redis 中的数据类型不正确，taskId: {}", taskId);
        return null;
    }

    private String buildPlanCacheKey(String taskId) {
        return String.format("assistant:plan:%s", taskId);
    }

    public TaskQueryResponse queryTask(String taskId) {
        String taskCacheKey = buildTaskCacheKey(taskId);
        AgentTaskVO taskVO = (AgentTaskVO) redisTemplate.opsForValue().get(taskCacheKey);

        if (taskVO == null) {
            log.warn("任务未找到或已过期，taskId: {}", taskId);
            return null;
        }

        List<TaskStep> steps = getPlanFromRedis(taskId);
        
        PlanVO plan = null;
        if (steps != null && !steps.isEmpty()) {
            plan = PlanVO.builder()
                    .version(2)
                    .steps(steps)
                    .build();
        }

        String status = determineTaskStatus(steps);

        TaskQueryResponse response = TaskQueryResponse.builder()
                .taskId(taskId)
                .status(status)
                .goal(taskVO.getGoal())
                .mode(taskVO.getMode())
                .plan(plan)
                .pendingApproval(null)
                .result(null)
                .error(null)
                .updatedAt(LocalDateTime.now())
                .build();

        return response;
    }

    private String determineTaskStatus(List<TaskStep> steps) {
        if (steps == null || steps.isEmpty()) {
            return "planning";
        }

        boolean hasRunning = false;
        boolean hasPending = false;
        boolean hasFailed = false;

        for (TaskStep step : steps) {
            String status = step.getStatus();
            if ("running".equals(status)) {
                hasRunning = true;
            } else if ("pending".equals(status)) {
                hasPending = true;
            } else if ("failed".equals(status)) {
                hasFailed = true;
            }
        }

        if (hasFailed) {
            return "failed";
        } else if (hasRunning) {
            return "running";
        } else if (hasPending) {
            return "planning";
        } else {
            return "completed";
        }
    }

    private String buildTaskCacheKey(String taskId) {
        return String.format("assistant:task:%s", taskId);
    }
}
