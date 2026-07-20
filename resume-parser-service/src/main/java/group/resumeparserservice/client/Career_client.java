package group.resumeparserservice.client;/* I love coding */

import group.common.Result;
import group.vo.AnalyzeVO;
import group.vo.MatchFilter;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@FeignClient(name = "career-service")
public interface Career_client {
//    细化推荐范围并重新推荐
    @PostMapping("/users/me/match/recommendations/refine")
   Result queryMatchFilters(@RequestBody MatchFilter filters);

    @GetMapping("/jobs/{jobId}")
    Result queryJobById(@PathVariable String jobId);

    @PostMapping("/users/me/match/analyze")
    Result AnalyzeJobs(@RequestBody AnalyzeVO analyzeVO);

    @PostMapping("/users/me/career-path/auto-plan")
    Result autoPlan(@RequestBody Map<String, String> requestBody);

    @GetMapping("/users/me/career-path/detail")
    Result getCareerPathDetail(
            @RequestParam(value = "pathId", required = false) String pathId,
            @RequestParam(value = "draftId", required = false) String draftId
    );

    @GetMapping("/users/me/career-report/{reportId}")
    public Result queryReport(@PathVariable String reportId);

    @PostMapping("/users/me/career-report/{reportId}/polish")
    public Result createPolishTask(@PathVariable String reportId, @RequestBody Map<String, Object> requestBody);

    @PostMapping("/users/me/career-report/generate")
    public Result generateCareerReport(@RequestBody Map<String, Object> requestBody);
}
