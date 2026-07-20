package group.careerservice.mq;

import group.careerservice.common.AsyncTaskErrorStorage;
import group.careerservice.domain.dto.JobImportRequestDTO;
import group.careerservice.service.JobImport.JobImportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class JobImportConsumer {

    private final JobImportService jobImportService;
    private final AsyncTaskErrorStorage asyncTaskErrorStorage;

    @RabbitListener(queues = "job.import")
    public void handleJobImportMessage(Map<String, Object> message) {
        String importJobId = (String) message.get("importJobId");
        String batchId = (String) message.get("batchId");
        JobImportRequestDTO request = (JobImportRequestDTO) message.get("request");

        log.info("接收到岗位导入任务消息，importJobId: {}, batchId: {}", importJobId, batchId);

        try {
            jobImportService.processImportJob(importJobId, batchId, request);
        } catch (Exception e) {
            log.error("岗位导入任务处理失败，importJobId: {}", importJobId, e);
            String errorMessage = e.getMessage() != null ? e.getMessage() : "任务执行失败";
            if (errorMessage.contains("文件") || errorMessage.contains("CSV") || errorMessage.contains("JSON")) {
                asyncTaskErrorStorage.storeError(importJobId, "JOB_IMPORT", 400, "源文件读取失败，请检查文件格式", errorMessage);
            } else if (errorMessage.contains("Elasticsearch") || errorMessage.contains("ES")) {
                asyncTaskErrorStorage.storeError(importJobId, "JOB_IMPORT", 503, "搜索引擎服务暂时不可用", errorMessage);
            } else {
                asyncTaskErrorStorage.storeError(importJobId, "JOB_IMPORT", 500, "任务执行失败", errorMessage);
            }
            throw e;
        }
    }
}
