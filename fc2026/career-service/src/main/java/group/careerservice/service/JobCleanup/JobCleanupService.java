package group.careerservice.service.JobCleanup;

import group.careerservice.domain.dto.JobCleanupRequestDTO;
import group.careerservice.domain.dto.JobCleanupResponseDTO;
import group.careerservice.domain.dto.JobDocument;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class JobCleanupService {

    private final ElasticsearchRestTemplate elasticsearchRestTemplate;

    public JobCleanupResponseDTO cleanupExpiredJobs(JobCleanupRequestDTO request) {
        Integer expireDays = request.getExpireDays() != null ? request.getExpireDays() : 45;
        Boolean dryRun = request.getDryRun() != null ? request.getDryRun() : false;

        log.info("开始清理过期岗位，过期天数: {}, 试运行模式: {}", expireDays, dryRun);

        LocalDateTime expireTime = LocalDateTime.now().minusDays(expireDays);
        String expireTimeStr = expireTime.format(DateTimeFormatter.ISO_DATE_TIME);

        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();
        boolQuery.must(QueryBuilders.rangeQuery("updatedAtNormalized").lt(expireTimeStr));

        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(boolQuery)
                .build();

        SearchHits<JobDocument> searchHits = elasticsearchRestTemplate.search(query, JobDocument.class);

        List<String> expiredJobIds = new ArrayList<>();
        searchHits.forEach(hit -> expiredJobIds.add(hit.getContent().getJobId()));

        int removedCount = expiredJobIds.size();

        if (!dryRun && removedCount > 0) {
            for (String jobId : expiredJobIds) {
                try {
                    elasticsearchRestTemplate.delete(jobId, JobDocument.class);
                    log.info("已删除过期岗位: {}", jobId);
                } catch (Exception e) {
                    log.error("删除过期岗位失败: {}", jobId, e);
                    removedCount--;
                }
            }
        }

        JobCleanupResponseDTO response = new JobCleanupResponseDTO();
        response.setExpireDays(expireDays);
        response.setRemovedCount(removedCount);
        response.setArchiveCount(removedCount);
        response.setExecutedAt(LocalDateTime.now().format(DateTimeFormatter.ISO_DATE_TIME));

        if (dryRun) {
            log.info("试运行模式完成，发现 {} 条过期岗位记录", removedCount);
        } else {
            log.info("过期岗位清理完成，共删除 {} 条记录", removedCount);
        }

        return response;
    }
}
