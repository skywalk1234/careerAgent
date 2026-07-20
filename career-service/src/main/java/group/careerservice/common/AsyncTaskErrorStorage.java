package group.careerservice.common;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.LocalDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
public class AsyncTaskErrorStorage {

    private final RedisTemplate<String, Object> redisTemplate;
    private final ObjectMapper objectMapper;

    private static final String ERROR_KEY_PREFIX = "async:task:error:";
    private static final Duration ERROR_TTL = Duration.ofMinutes(15);

    @Data
    public static class ErrorInfo {
        private String taskId;
        private String taskType;
        private Integer errorCode;
        private String errorMessage;
        private String errorDetail;
        private LocalDateTime errorTime;

        public ErrorInfo() {
            this.errorTime = LocalDateTime.now();
        }

        public ErrorInfo(String taskId, String taskType, Integer errorCode, String errorMessage, String errorDetail) {
            this();
            this.taskId = taskId;
            this.taskType = taskType;
            this.errorCode = errorCode;
            this.errorMessage = errorMessage;
            this.errorDetail = errorDetail;
        }
    }

    public void storeError(String taskId, String taskType, Integer errorCode, String errorMessage, String errorDetail) {
        try {
            String key = ERROR_KEY_PREFIX + taskId;
            ErrorInfo errorInfo = new ErrorInfo(taskId, taskType, errorCode, errorMessage, errorDetail);
            redisTemplate.opsForValue().set(key, errorInfo, ERROR_TTL);
            log.warn("异步任务错误已暂存到Redis, taskId: {}, taskType: {}, errorCode: {}", taskId, taskType, errorCode);
        } catch (Exception e) {
            log.error("存储异步任务错误到Redis失败, taskId: {}", taskId, e);
        }
    }

    public void storeError(String taskId, String taskType, Exception exception) {
        String errorMessage = exception.getMessage();
        String errorDetail = getStackTraceAsString(exception);
        storeError(taskId, taskType, 500, errorMessage, errorDetail);
    }

    public ErrorInfo getError(String taskId) {
        try {
            String key = ERROR_KEY_PREFIX + taskId;
            Object value = redisTemplate.opsForValue().get(key);
            if (value != null) {
                return objectMapper.convertValue(value, ErrorInfo.class);
            }
        } catch (Exception e) {
            log.error("从Redis获取异步任务错误失败, taskId: {}", taskId, e);
        }
        return null;
    }

    public boolean hasError(String taskId) {
        try {
            String key = ERROR_KEY_PREFIX + taskId;
            return Boolean.TRUE.equals(redisTemplate.hasKey(key));
        } catch (Exception e) {
            log.error("检查Redis错误状态失败, taskId: {}", taskId, e);
            return false;
        }
    }

    public void clearError(String taskId) {
        try {
            String key = ERROR_KEY_PREFIX + taskId;
            redisTemplate.delete(key);
            log.info("已清除Redis错误记录, taskId: {}", taskId);
        } catch (Exception e) {
            log.error("清除Redis错误记录失败, taskId: {}", taskId, e);
        }
    }

    private String getStackTraceAsString(Exception exception) {
        StringBuilder sb = new StringBuilder();
        sb.append(exception.toString()).append("\n");
        for (StackTraceElement element : exception.getStackTrace()) {
            sb.append("\tat ").append(element.toString()).append("\n");
        }
        return sb.toString();
    }
}
