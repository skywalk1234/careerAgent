package group.resumeparserservice.config;/* I love coding */

import com.alibaba.cloud.ai.dashscope.chat.DashScopeChatModel;
import com.alibaba.cloud.ai.dashscope.embedding.DashScopeEmbeddingModel;
import com.openai.models.embeddings.EmbeddingModel;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.MessageChatMemoryAdvisor;
import org.springframework.ai.chat.client.advisor.SimpleLoggerAdvisor;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.pgvector.PgVectorStore;
import org.springframework.beans.factory.annotation.Qualifier;
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
            DashScopeEmbeddingModel embeddingModel) {
        return PgVectorStore.builder(jdbcTemplate, embeddingModel)
                .vectorTableName("job_category_vector") //如果要改大类的向量化先改这里
                .dimensions(1536)  // 与嵌入模型维度对齐
                .distanceType(COSINE_DISTANCE)  // 余弦相似度计算
                .indexType(HNSW)  // 高效近似最近邻搜索
                .initializeSchema(false)
                .build();
    }

    @Bean
    public VectorStore jobDetailVectorStore(
            @Qualifier("vectorJdbcTemplate") JdbcTemplate jdbcTemplate,
            DashScopeEmbeddingModel embeddingModel) {
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
