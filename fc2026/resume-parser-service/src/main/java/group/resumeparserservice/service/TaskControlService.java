package group.resumeparserservice.service;/* I love coding */

import group.resumeparserservice.domain.vo.TaskControl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class TaskControlService {

    @Autowired
    private RedisTemplate<String, Object> redisTemplate;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    public void controlTask(String taskId, String action) {
        log.info("任务控制：taskId={}, action={}", taskId, action);

        TaskControl control = getTaskControl(taskId);
        if (control == null) {
            control = TaskControl.builder()
                    .taskId(taskId)
                    .status("running")
                    .build();
        }

        LocalDateTime now = LocalDateTime.now();

        switch (action) {
            case "pause":
                control.setStatus("paused");
                control.setAction("pause");
                control.setPausedAt(now);
                control.setUpdatedAt(now);
                break;

            case "resume":
                control.setStatus("running");
                control.setAction("resume");
                control.setResumedAt(now);
                control.setUpdatedAt(now);
                break;

            case "cancel":
                control.setStatus("cancelled");
                control.setAction("cancel");
                control.setUpdatedAt(now);
                break;

            case "retry":
                control.setStatus("running");
                control.setAction("retry");
                control.setUpdatedAt(now);
                break;

            default:
                throw new IllegalArgumentException("不支持的控制动作：" + action);
        }

        saveTaskControl(taskId, control);

        publishControlMessage(taskId, action);
    }

    public TaskControl getTaskControl(String taskId) {
        String key = buildControlCacheKey(taskId);
        Object obj = redisTemplate.opsForValue().get(key);

        if (obj instanceof TaskControl) {
            return (TaskControl) obj;
        }

        if (obj instanceof Map) {
            return mapToTaskControl((Map<?, ?>) obj);
        }

        return null;
    }

    public void saveTaskControl(String taskId, TaskControl control) {
        String key = buildControlCacheKey(taskId);
        redisTemplate.opsForValue().set(key, control, 300000, TimeUnit.SECONDS);
        log.info("任务控制状态已保存：taskId={}, status={}", taskId, control.getStatus());
    }

    public void publishControlMessage(String taskId, String action) {
        String channel = "task_control:" + taskId;
        stringRedisTemplate.convertAndSend(channel, action);
        log.info("已发布控制消息：channel={}, action={}", channel, action);
    }

    public void updateTaskControlStatus(String taskId, String status, String currentStepId) {
        TaskControl control = getTaskControl(taskId);
        if (control == null) {
            control = TaskControl.builder()
                    .taskId(taskId)
                    .status(status)
                    .currentStepId(currentStepId)
                    .updatedAt(LocalDateTime.now())
                    .build();
        } else {
            control.setStatus(status);
            control.setCurrentStepId(currentStepId);
            control.setUpdatedAt(LocalDateTime.now());
        }
        saveTaskControl(taskId, control);
    }

    private TaskControl mapToTaskControl(Map<?, ?> map) {
        return TaskControl.builder()
                .taskId((String) map.get("taskId"))
                .status((String) map.get("status"))
                .currentStepId((String) map.get("currentStepId"))
                .action((String) map.get("action"))
                .pausedAt((LocalDateTime) map.get("pausedAt"))
                .resumedAt((LocalDateTime) map.get("resumedAt"))
                .updatedAt((LocalDateTime) map.get("updatedAt"))
                .build();
    }

    private String buildControlCacheKey(String taskId) {
        return String.format("assistant:task_control:%s", taskId);
    }
}
