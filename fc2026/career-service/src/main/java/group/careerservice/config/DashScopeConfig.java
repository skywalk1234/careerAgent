package group.careerservice.config;

import com.alibaba.dashscope.aigc.generation.Generation;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DashScopeConfig {

    @Value("${dashscope.api-key:}")
    private String apiKey;

    @Bean
    public Generation dashScopeGeneration() {
        return new Generation();
    }

    public String getApiKey() {
        return apiKey;
    }
}
