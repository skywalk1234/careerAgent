package group.resumeparserservice.config;/* I love coding */

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingOptions;
import com.openai.models.embeddings.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.document.MetadataMode;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgDistanceType.COSINE_DISTANCE;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.HNSW;
import static org.springframework.ai.vectorstore.pgvector.PgVectorStore.PgIndexType.IVFFLAT;

@Configuration
@Slf4j
public class SpringAIConfig {
//    @Bean
//    public ChatClient chatClient(DashScopeChatModel model, ChatMemory chatMemory) {
//        return ChatClient.builder(model)
//                .defaultSystem("你是一个简历解析器，根据简历的文本信息提取对应内容")
//                .defaultAdvisors(
//                        new SimpleLoggerAdvisor(),
//                        MessageChatMemoryAdvisor.builder(chatMemory).build())
//                .build();
//    }

    @Bean
    public ChatClient normalChatClient(DashScopeChatModel chatModel){
        return ChatClient.builder(chatModel)  // 创建ChatClient工厂
                .build(); // 构建ChatClient实例
    }

//    @Bean
//    @Qualifier("streamingChatClient")
//    public ChatClient streamingChatClient(DashScopeChatModel chatModel) {
//        log.info("创建流式ChatClient，使用模型: qwen-plus");
//        return ChatClient.builder(chatModel)
//                .defaultOptions(ChatOptions.builder()
//                        .temperature(0.7d)
//                        .model("qwen-plus")
//                        .build())
//                .build();
//    }

    @Bean
    public VectorStore pgVectorVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            // 必须显式限定：本类还定义了 jobDetailEmbeddingModel，同类型 bean 有两个，
            // 不限定会 NoUniqueBeanDefinitionException
            @Qualifier("categoryEmbeddingModel") DashScopeEmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("job_category_vector") //如果要改大类的向量化先改这里
                .dimensions(1536)  // 与嵌入模型维度对齐
                .distanceType(COSINE_DISTANCE)  // 余弦相似度计算
                .indexType(HNSW)  // 高效近似最近邻搜索
                .initializeSchema(false)
                .build();
    }

    /**
     * job_detail_vector 专用嵌入模型：与 Python 侧 crawler/db.py 写库时对齐
     * （qwen3.7-text-embedding / 1536 维 / text_type=document）。
     * <p>
     * text_type 不显式设置，走 DashScopeApi.DEFAULT_EMBEDDING_TEXT_TYPE（= document，已反编译确认），
     * 与 Python 写 content 时的 text_type=document 同处一个向量空间；该 bean 同时承担
     * jobDetailVectorStore 的查询侧（AI_recommend / AI_route_planning），也落在同一空间。
     * （注意 MetadataMode 是 Spring AI 控制「metadata 要不要拼进向量输入」的枚举，与 text_type 无关）
     * <p>
     * 注意：不能改 application.yml 的 spring.ai.dashscope.embedding.options.model —— 那会连带
     * 改掉 job_category_vector 用的 categoryEmbeddingModel，而那张表的 38 条向量仍是 text-embedding-v1。
     */
    @Bean("jobDetailEmbeddingModel")
    public DashScopeEmbeddingModel jobDetailEmbeddingModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return new DashScopeEmbeddingModel(
                dashScopeApi,
                MetadataMode.NONE,  // 只嵌 Document 正文，不把 metadata 拼进向量输入
                DashScopeEmbeddingOptions.builder()
                        .withModel("qwen3.7-text-embedding")
                        .withDimensions(1536)
                        .build());
    }

    /**
     * job_category_vector 专用嵌入模型（text-embedding-v1 / 1536 维），保持与该表已有向量同一空间。
     * <p>
     * 之所以要显式声明：自动配置 DashScopeEmbeddingAutoConfiguration#dashscopeEmbeddingModel
     * 带 @ConditionalOnMissingBean，本类一旦存在 DashScopeEmbeddingModel 类型的 bean（jobDetailEmbeddingModel），
     * 自动配置就不再创建 dashscopeEmbeddingModel，所以这里补上它。
     */
    @Bean("categoryEmbeddingModel")
    public DashScopeEmbeddingModel categoryEmbeddingModel(
            @Value("${spring.ai.dashscope.api-key}") String apiKey,
            @Value("${spring.ai.dashscope.base-url:https://dashscope.aliyuncs.com}") String baseUrl) {
        DashScopeApi dashScopeApi = DashScopeApi.builder()
                .apiKey(apiKey)
                .baseUrl(baseUrl)
                .build();
        return new DashScopeEmbeddingModel(
                dashScopeApi,
                MetadataMode.EMBED,  // 与自动配置默认值一致
                DashScopeEmbeddingOptions.builder()
                        .withModel("text-embedding-v1")
                        .withDimensions(1536)
                        .build());
    }

    @Bean
    public VectorStore jobDetailVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            @Qualifier("jobDetailEmbeddingModel") DashScopeEmbeddingModel embeddingModel) {
        System.out.println(">>> [DEBUG] 正在创建指向表 'job_detail_vector' 的 VectorStore Bean...");
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("job_detail_vector") //如果要改大类的向量化先改这里
                .dimensions(1536)  // 与嵌入模型维度对齐
                .distanceType(COSINE_DISTANCE)  // 余弦相似度计算
                .indexType(HNSW)  // 高效近似最近邻搜索
                .initializeSchema(false)
                .build();
    }


    @Bean("vectorJdbcTemplate")
    public JdbcTemplate jdbcTemplate(DataSource dataSource) {
        return new JdbcTemplate(dataSource);
    }
}
