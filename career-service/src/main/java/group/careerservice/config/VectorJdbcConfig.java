package group.careerservice.config;/* I love coding */

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 岗位向量库（pgvector，job_detail_vector）的 JDBC 接入。
 *
 * <p><b>这里刻意只暴露 {@link JdbcTemplate} Bean，不暴露 {@code DataSource} Bean。</b>
 * career-service 的主数据源是 Nacos shared-jdbc.yaml 里的 MySQL，MyBatis-Plus 的
 * {@code MybatisPlusAutoConfiguration} 类上标了 {@code @ConditionalOnSingleCandidate(DataSource.class)}：
 * 容器里一旦多出第二个 DataSource 候选，该条件不再成立，<b>全部 {@code @Mapper} 会静默不装配</b>
 * （favorite_jobs 等直接报错）。所以 Hikari 数据源在方法内部 new 出来、包进 JdbcTemplate 返回，
 * 不注册成独立 Bean。加 {@code @Primary} 也不是解法——那会把 MyBatis 指向 PG。
 *
 * <p><b>副作用提醒：</b>这里返回的 {@code JdbcTemplate} 是容器里唯一的 {@code JdbcOperations}，
 * 会让 Spring Boot 的 {@code JdbcTemplateAutoConfiguration}（同样带
 * {@code @ConditionalOnMissingBean(JdbcOperations.class)}）不再自动配置基于 MySQL 的
 * {@code jdbcTemplate}。本模块目前没有别处用 JdbcTemplate，所以无影响；
 * 以后若要写 MySQL 的裸 SQL，请用 MyBatis-Plus，或另起一个带名字的 Bean 显式注入。
 */
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

    @Bean(name = "vectorJdbcTemplate")
    public JdbcTemplate vectorJdbcTemplate() {
        log.info("初始化岗位向量库 JdbcTemplate，url: {}", url);
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(url);
        config.setUsername(username);
        config.setPassword(password);
        config.setMaximumPoolSize(maxPoolSize);
        config.setPoolName("vector-pool");
        // PG 侧表由人工 DDL 建立，这里不跑任何初始化脚本
        config.setInitializationFailTimeout(-1);
        return new JdbcTemplate(new HikariDataSource(config));
    }
}
