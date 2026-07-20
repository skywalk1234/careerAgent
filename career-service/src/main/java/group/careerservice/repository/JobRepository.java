package group.careerservice.repository;

import group.careerservice.domain.dto.JobDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface JobRepository extends ElasticsearchRepository<JobDocument, String> {
    // 通过 jobId 查询
    JobDocument findByJobId(String jobId);


}