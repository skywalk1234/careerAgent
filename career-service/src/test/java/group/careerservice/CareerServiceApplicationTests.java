package group.careerservice;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.TestConstant.AI_recommendation;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.dto.MatchJob;
import group.careerservice.service.JobExploration.SaveJobService;
import lombok.RequiredArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
@RequiredArgsConstructor
class CareerServiceApplicationTests {
    @Autowired
    private SaveJobService saveJobService;
    @Test
    void contextLoads() {
    }
    @Test
    void selectJob() {
        //完美的查询成功
        String jobId = "100";
        JobDocument jobDocument = saveJobService.queryJobById(jobId);
        System.out.println(jobDocument.getCity());
        System.out.println(jobDocument.getCompanyName());
        System.out.println(jobDocument.getCompanySize());
        System.out.println(jobDocument.getCompanyType());
        System.out.println(jobDocument.getDistrict());
        System.out.println(jobDocument.getEducationRequirement());
        System.out.println(jobDocument.getIndustryTags());
        System.out.println(jobDocument.getJobDescription());

    }

    @Test
    void testMapping() throws JsonProcessingException {
        ObjectMapper objectMapper = new ObjectMapper();
        String json = AI_recommendation.recommendation;
        MatchJob matchJob = objectMapper.readValue(json, MatchJob.class);
        System.out.println(matchJob.getBestMatch().getJobName());
        System.out.println(matchJob.getBestMatch().getCompanyName());
        System.out.println(matchJob.getBestMatch().getCity());
        System.out.println(matchJob.getBestMatch().getEducationRequirement());
        System.out.println(matchJob.getBestMatch().getSalaryNormalized());
    }

}
