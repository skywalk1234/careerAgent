package group.careerservice.mq;

import group.careerservice.common.AsyncTaskErrorStorage;
import group.careerservice.domain.dto.JobEdgeGenerateRequestDTO;
import group.careerservice.service.JobEdgeGeneration.JobEdgeGenerationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobEdgeGenerateConsumer {

    private final JobEdgeGenerationService jobEdgeGenerationService;
    private final AsyncTaskErrorStorage asyncTaskErrorStorage;

    @RabbitListener(queues = "job.edge.generate")
    public void handleJobEdgeGenerateMessage(Map<String, Object> message) {
        String edgeJobId = (String) message.get("edgeJobId");
        String batchId = (String) message.get("batchId");
        Map<String, Object> strategyMap = (Map<String, Object>) message.get("strategy");

        log.info("接收到岗位关系边生成任务消息，edgeJobId: {}, batchId: {}", edgeJobId, batchId);

        try {
            JobEdgeGenerateRequestDTO.EdgeStrategy strategy = convertToStrategy(strategyMap);
            jobEdgeGenerationService.processEdgeGeneration(edgeJobId, batchId, strategy);
        } catch (Exception e) {
            log.error("岗位关系边生成任务处理失败，edgeJobId: {}", edgeJobId, e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "AI服务调用失败";
            if (errorMessage.contains("context length") || errorMessage.contains("token")) {
                asyncTaskErrorStorage.storeError(edgeJobId, "JOB_EDGE_GENERATE", 413, "上下文长度超限", errorMessage);
            } else if (errorMessage.contains("API") || errorMessage.contains("DashScope") || errorMessage.contains("模型服务")) {
                asyncTaskErrorStorage.storeError(edgeJobId, "JOB_EDGE_GENERATE", 503, "AI服务暂时不可用，建议稍后重试", errorMessage);
            } else {
                asyncTaskErrorStorage.storeError(edgeJobId, "JOB_EDGE_GENERATE", 500, "任务执行失败", errorMessage);
            }
            throw e;
        }
    }

    private JobEdgeGenerateRequestDTO.EdgeStrategy convertToStrategy(Map<String, Object> strategyMap) {
        JobEdgeGenerateRequestDTO.EdgeStrategy strategy = new JobEdgeGenerateRequestDTO.EdgeStrategy();
        if (strategyMap != null) {
            if (strategyMap.get("minSimilarity") instanceof Number) {
                strategy.setMinSimilarity(((Number) strategyMap.get("minSimilarity")).doubleValue());
            }
            if (strategyMap.get("includePromotion") instanceof Boolean) {
                strategy.setIncludePromotion((Boolean) strategyMap.get("includePromotion"));
            }
            if (strategyMap.get("includeTransition") instanceof Boolean) {
                strategy.setIncludeTransition((Boolean) strategyMap.get("includeTransition"));
            }
            if (strategyMap.get("maxOutDegree") instanceof Number) {
                strategy.setMaxOutDegree(((Number) strategyMap.get("maxOutDegree")).intValue());
            }
        }
        return strategy;
    }
}
