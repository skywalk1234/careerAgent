package group.career_backend.job_function.job_analyze.controller;

import group.career_backend.common.Result;
import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobRequest;
import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobResult;
import group.career_backend.job_function.job_analyze.domain.dto.JobBrief;
import group.career_backend.job_function.job_analyze.domain.dto.MatchFilter;
import group.career_backend.job_function.job_analyze.domain.dto.MatchJob;
import group.career_backend.job_function.job_analyze.domain.po.AnalyzeJob;
import group.career_backend.job_function.job_analyze.domain.po.RecommendJob;
import group.career_backend.job_function.job_analyze.service.JobAnalyzeService;
import group.career_backend.job_function.job_analyze.service.RecommendationService;
import group.career_backend.job_function.job_analyze.service.impl.RecommendationServiceImpl;
import group.career_backend.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
public class JobAnalyzeController {
    private final RecommendationService recommendationService;
    private final JobAnalyzeService jobAnalyzeService;

    @GetMapping("/users/me/match/recommendations")
    public Result<?> recommendations(@RequestParam(value = "topN", defaultValue = "10") Integer topN,
                                     HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        int limit = RecommendationServiceImpl.normalizeTopN(topN);
        log.info("[接口访问] GET /users/me/match/recommendations, userId={}, topN={}", userId, limit);
        RecommendJob record = recommendationService.getLatest(userId, limit);
        Map<String, Object> data = new LinkedHashMap<>();
        if (record == null || record.getMatchResult() == null) {
            data.put("recommendationStatus", "processing");
            data.put("reason", "人岗匹配分析中，请稍候。");
            data.put("pollAfterMs", 1500);
            data.put("bestMatch", null);
            data.put("otherRecommendations", new ArrayList<>());
            log.info("[接口完成] 暂无岗位推荐结果, userId={}", userId);
            return Result.success(data);
        }

        MatchJob result = record.getMatchResult();
        data.put("recommendationStatus", "completed");
        data.put("recommendedAt", record.getCreatedAt() == null ? null : record.getCreatedAt().toString());
        data.put("bestMatch", result.getBestMatch());
        data.put("otherRecommendations", result.getOtherRecommendations());
        log.info("[接口完成] 查询岗位推荐成功, userId={}, recordId={}", userId, record.getId());
        return Result.success(data);
    }

    @PostMapping("/users/me/match/recommendations/refine")
    public Result<?> refine(@RequestBody MatchFilter filters, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        if (filters == null || filters.getScope() == null) {
            return Result.error(400, "filters.scope 不能为空");
        }
        int topN = RecommendationServiceImpl.normalizeTopN(filters.getTopN());
        filters.setTopN(topN);
        log.info("[接口访问] POST /users/me/match/recommendations/refine, userId={}, topN={}", userId, topN);
        recommendationService.requestRecommendation(userId, filters);

        MatchFilter.Scope scope = filters.getScope();
        Map<String, Object> responseScope = new LinkedHashMap<>();
        responseScope.put("preferredJobKeywords", scope.getPreferredJobKeywords());
        responseScope.put("cityIntents", scope.getCityIntents());
        responseScope.put("salaryRange", scope.getSalaryRange());
        responseScope.put("includeSimilarJobs", Boolean.TRUE.equals(scope.getIncludeSimilarJobs()));
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recommendationStatus", "processing");
        data.put("pollAfterMs", 1500);
        data.put("topN", topN);
        data.put("scope", responseScope);
        log.info("[接口完成] 岗位推荐消息已发送, userId={}", userId);
        return Result.success(data);
    }

    @PostMapping("/users/me/match/analyze")
    public Result<?> analyze(@RequestBody AnalyzeJobRequest body, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        if (body == null || body.getJobId() == null || body.getJobId().isBlank()) {
            return Result.error(400, "jobId 不能为空");
        }
        log.info("[接口访问] POST /users/me/match/analyze, userId={}, jobId={}", userId, body.getJobId());
        JobAnalyzeService.AnalyzeOutcome outcome = jobAnalyzeService.analyze(userId, body);
        Map<String, Object> data = detailMap(outcome.record(), body.getSource(), body.getSourceMeta());
        data.put("overwritten", outcome.overwritten());
        data.put("removedRecordId", outcome.removedRecordId() == null ? null : outcome.removedRecordId().toString());
        log.info("[接口完成] 岗位分析成功, userId={}, recordId={}", userId, outcome.record().getId());
        return Result.success(data);
    }

    @GetMapping("/users/me/match/history/{recordId}")
    public Result<?> historyDetail(@PathVariable String recordId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        Long id;
        try {
            id = Long.valueOf(recordId);
        } catch (NumberFormatException exception) {
            return Result.error(400, "recordId 格式不正确");
        }
        log.info("[接口访问] GET /users/me/match/history/{recordId}, userId={}, recordId={}", userId, id);
        AnalyzeJob record = jobAnalyzeService.getDetail(userId, id);
        if (record == null || record.getAnalysis() == null) {
            log.info("[接口完成] 岗位分析历史不存在或无权访问, userId={}, recordId={}", userId, id);
            return Result.error(404, "分析记录不存在或无权访问");
        }
        return Result.success(detailMap(record, "favorite", Map.of("from", "favorite-panel")));
    }

    @GetMapping("/users/me/match/history")
    public Result<?> history(HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/match/history, userId={}", userId);
        List<JobBrief> list = jobAnalyzeService.getHistory(userId);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("total", list.size());
        data.put("pinnedRecordId", null);
        data.put("list", list);
        log.info("[接口完成] 查询岗位分析历史成功, userId={}, total={}", userId, list.size());
        return Result.success(data);
    }

    private Map<String, Object> detailMap(AnalyzeJob record, String source, Map<String, String> sourceMeta) {
        AnalyzeJobResult result = record.getAnalysis();
        String databaseTime = record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("recordId", record.getId().toString());
        data.put("source", source == null || source.isBlank() ? "favorite" : source);
        data.put("sourceMeta", sourceMeta == null ? Map.of("from", "favorite-panel") : sourceMeta);
        data.put("job", result.getJob());
        data.put("analysis", result.getAnalysis());
        data.put("createdAt", databaseTime);
        data.put("updatedAt", databaseTime);
        data.put("pinned", false);
        data.putIfAbsent("overwritten", false);
        data.putIfAbsent("removedRecordId", null);
        return data;
    }
}
