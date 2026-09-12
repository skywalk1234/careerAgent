package group.career_backend.profile.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.support.converter.JacksonJsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMqConfig {
    @Bean
    public Queue fileTransferQueue() {
        return new Queue("file_tran", true);
    }

    @Bean
    public Queue profileStorageQueue() {
        return new Queue("profile_storage", true);
    }

    @Bean
    public Queue recommendationQueue() {
        return new Queue("recommend", true);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new JacksonJsonMessageConverter();
    }
}
