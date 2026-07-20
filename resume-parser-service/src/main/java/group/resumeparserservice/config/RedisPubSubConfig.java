package group.resumeparserservice.config;/* I love coding */

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.listener.PatternTopic;
import org.springframework.data.redis.listener.RedisMessageListenerContainer;
import org.springframework.data.redis.listener.adapter.MessageListenerAdapter;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

@Configuration
@Slf4j
public class RedisPubSubConfig {

    @Bean
    public RedisMessageListenerContainer redisMessageListenerContainer(
            RedisConnectionFactory connectionFactory,
            MessageListenerAdapter messageListenerAdapter) {
        
        RedisMessageListenerContainer container = new RedisMessageListenerContainer();
        container.setConnectionFactory(connectionFactory);
        container.setTaskExecutor(redisTaskExecutor());
        container.setRecoveryInterval(5000L);
        
        // 订阅 task_control:* 频道
        container.addMessageListener(messageListenerAdapter, new PatternTopic("task_control:*"));
        log.info("已订阅频道：task_control:*");
        
        // 订阅 approval:* 频道
        container.addMessageListener(messageListenerAdapter, new PatternTopic("approval:*"));
        log.info("已订阅频道：approval:*");
        
        log.info("Redis Message Listener Container 初始化完成");
        return container;
    }

    @Bean
    public MessageListenerAdapter messageListenerAdapter(RedisMessageSubscriber redisMessageSubscriber) {
        return new MessageListenerAdapter(redisMessageSubscriber, "onMessage");
    }

    @Bean
    public RedisMessageSubscriber redisMessageSubscriber() {
        return new RedisMessageSubscriber();
    }

    @Bean
    public ThreadPoolTaskExecutor redisTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(10);
        executor.setMaxPoolSize(50);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("redis-pubsub-");
        executor.initialize();
        return executor;
    }
}
