package group.career_backend.job_function.job_explore.config;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration
@Slf4j
public class VectorJdbcConfig {

    @Value("${vector.datasource.url:}")
    private String url;

    @Value("${vector.datasource.username:}")
    private String username;

    @Value("${vector.datasource.password:}")
    private String password;

    @Value("${vector.datasource.max-pool-size:4}")
    private int maxPoolSize;

    private HikariDataSource vectorDataSource;

    @Bean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate() {
        log.info("[数据源初始化] 创建岗位向量库连接池, url={}, maxPoolSize={}", url, maxPoolSize);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName("job-explore-vector-pool");
        config.setInitializationFailTimeout(-1);
        vectorDataSource = new HikariDataSource(config);
        return new JdbcTemplate(vectorDataSource);
    }

    @PreDestroy
    public void closeVectorDataSource() {
        if (vectorDataSource != null) {
            log.info("[数据源关闭] 关闭岗位向量库连接池");
            vectorDataSource.close();
        }
    }
}
