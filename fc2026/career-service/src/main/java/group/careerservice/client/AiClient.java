package group.careerservice.client;/* I love coding */

import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.Map;

@FeignClient(name = "resume-parser-service")
public interface AiClient {
    @PostMapping("/eval/realTime")
    public String realTimeEval(@RequestBody String prompt);

    @PostMapping("/eval/deep")
    public String deepEval(@RequestBody String prompt);

    @PostMapping("/roadmapping/create")
    public String createRoadmapping(@RequestBody Map<String, String> request);

    @PostMapping("/roadmapping/create")
    public String createRoadmapping(@RequestBody String request_json);

    @PostMapping("/job/analyze")
    public String analyzeJobs(@RequestBody String request_json);

    //    推荐岗位大类
    @PostMapping("/jobs/recommend")
    public String recommendJobs(@RequestBody String request_json);
//  推荐具体岗位
    @PostMapping("/jobs/recommend/specific")
    public String recommendSpecificJobs(@RequestBody String request_json);
//    自动规划路径
    @PostMapping("/jobs/route/auto-plan")
    public String routePlanning(@RequestBody Map<String, Object> request);
//    生涯报告润色
    @PostMapping("/roadmapping/polish")
    public String polishRoadmapping(@RequestBody String request_json);
}

