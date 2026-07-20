package group.resumeparserservice.config;/* I love coding */

import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.connection.Message;
import org.springframework.data.redis.connection.MessageListener;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

@Component
@Slf4j
public class RedisMessageSubscriber implements MessageListener {

    private final ConcurrentHashMap<String, CountDownLatch> latchMap = new ConcurrentHashMap<>();
    
    private final ConcurrentHashMap<String, String> approvalResultMap = new ConcurrentHashMap<>();

    @Override
    public void onMessage(Message message, byte[] pattern) {
        try {
            String channel = new String(message.getChannel(), StandardCharsets.UTF_8);
            String body = new String(message.getBody(), StandardCharsets.UTF_8);

            log.info("📩 收到 Redis 消息：channel={}, message={}", channel, body);

            if (channel.startsWith("task_control:")) {
                String taskId = channel.substring("task_control:".length());
                log.info("识别为任务控制消息：taskId={}", taskId);
                handleTaskControlMessage(taskId, body);
            } else if (channel.startsWith("approval:")) {
                String approvalId = channel.substring("approval:".length());
                log.info("识别为审批消息：approvalId={}", approvalId);
                handleApprovalMessage(approvalId, body);
            } else {
                log.warn("未知频道：channel={}", channel);
            }
        } catch (Exception e) {
            log.error("处理 Redis 消息失败", e);
        }
    }

    private void handleTaskControlMessage(String taskId, String action) {
        if ("resume".equals(action) || "cancel".equals(action) || "retry".equals(action)) {
            CountDownLatch latch = latchMap.remove(taskId);
            if (latch != null) {
                log.info("唤醒任务：taskId={}, action={}", taskId, action);
                latch.countDown();
            } else {
                log.warn("未找到对应的 latch，taskId={}", taskId);
            }
        }
    }
    
    private void handleApprovalMessage(String approvalId, String decision) {
        log.info("处理审批消息：approvalId={}, decision={}", approvalId, decision);
        
        approvalResultMap.put(approvalId, decision);
        
        CountDownLatch latch = latchMap.remove(approvalId);
        if (latch != null) {
            log.info("✅ 唤醒审批等待：approvalId={}, decision={}, latchMap 剩余={}", approvalId, decision, latchMap.size());
            latch.countDown();
        } else {
            log.warn("❌ 未找到审批对应的 latch，approvalId={}, 当前等待的 approvalId 列表={}", approvalId, latchMap.keySet());
        }
    }

    public void registerLatch(String key, CountDownLatch latch) {
        latchMap.put(key, latch);
        log.info("注册 latch：key={}", key);
    }

    public void removeLatch(String key) {
        latchMap.remove(key);
        log.info("移除 latch：key={}", key);
    }
    
    public String getApprovalResult(String approvalId) {
        return approvalResultMap.remove(approvalId);
    }
}
