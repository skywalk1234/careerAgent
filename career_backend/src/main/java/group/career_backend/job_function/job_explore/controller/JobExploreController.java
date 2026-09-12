package group.career_backend.job_function.job_explore.controller;

import group.career_backend.common.Result;
import group.career_backend.job_function.job_explore.domain.dto.JobVectorItem;
import group.career_backend.job_function.job_explore.domain.response.FavoriteRes;
import group.career_backend.job_function.job_explore.domain.response.JobFilterRes;
import group.career_backend.job_function.job_explore.domain.vo.JobVectorFilter;
import group.career_backend.job_function.job_explore.domain.vo.UserJobCreateRequest;
import group.career_backend.job_function.job_explore.service.FavoriteJobService;
import group.career_backend.job_function.job_explore.service.JobQueryService;
import group.career_backend.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.util.StringUtils;

import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class JobExploreController {

    private final JobQueryService jobQueryService;
    private final FavoriteJobService favoriteJobService;

    @GetMapping("/jobs/{jobId}")
    public Result<?> queryJobById(@PathVariable String jobId) {
        log.info("[接口访问] GET /jobs/{jobId}, jobId={}", jobId);
        JobVectorItem job = jobQueryService.getById(jobId);
        if (job == null) {
            log.info("[接口完成] 岗位详情查询失败, jobId={}", jobId);
            return Result.error(500, "查询失败");
        }
        log.info("[接口完成] 岗位详情查询成功, jobId={}", jobId);
        return Result.success(job);
    }

    @GetMapping("/jobs/filters")
    public Result<?> queryJobFilters() {
        log.info("[接口访问] GET /jobs/filters");
        JobFilterRes response = JobFilterRes.fromOptions(jobQueryService.getFilterOptions());
        log.info("[接口完成] 岗位筛选项查询成功");
        return Result.success(response);
    }

    @PostMapping("/jobs/search")
    public Result<?> queryJobsByFilter(@RequestBody(required = false) JobVectorFilter filters) {
        log.info("[接口访问] POST /jobs/search");
        Map<String, Object> response = jobQueryService.search(filters);
        log.info("[接口完成] 岗位筛选查询成功, total={}", response.get("total"));
        return Result.success(response);
    }

    @PostMapping("/users/me/favorite-jobs/custom")
    public Result<?> createAndFavoriteJob(@RequestBody UserJobCreateRequest requestBody,
                                          HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        if (requestBody == null || !StringUtils.hasText(requestBody.getTitle())) {
            return Result.error(400, "请填写岗位名称");
        }
        if (requestBody.getSalaryMin() != null && requestBody.getSalaryMax() != null
                && requestBody.getSalaryMin() > requestBody.getSalaryMax()) {
            return Result.error(400, "最低薪资不能高于最高薪资");
        }

        log.info("[接口访问] POST /users/me/favorite-jobs/custom, userId={}, title={}",
                userId, requestBody.getTitle());
        JobVectorItem job = jobQueryService.createUserJob(userId, requestBody);
        if (job == null || !StringUtils.hasText(job.getJobId())) {
            return Result.error(500, "岗位信息新增失败");
        }
        try {
            if (favoriteJobService.add(userId, job.getJobId())) {
                log.info("[接口完成] 用户岗位新增并收藏成功, userId={}, jobId={}", userId, job.getJobId());
                return Result.success(job, "岗位新增并收藏成功");
            }
        } catch (RuntimeException exception) {
            log.error("[接口异常] 用户岗位收藏失败，准备回滚PG岗位, userId={}, jobId={}",
                    userId, job.getJobId(), exception);
        }
        try {
            jobQueryService.deleteUserJob(job.getJobId());
        } catch (RuntimeException rollbackException) {
            log.error("[接口异常] 用户岗位回滚失败, userId={}, jobId={}",
                    userId, job.getJobId(), rollbackException);
        }
        return Result.error(500, "岗位收藏失败");
    }

    @GetMapping("/users/me/favorite-jobs")
    public Result<?> queryFavoriteJobs(HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/favorite-jobs, userId={}, authenticated={}",
                userId, UserContext.isAuthenticated(request));
        FavoriteRes response = favoriteJobService.list(userId);
        log.info("[接口完成] 用户收藏查询成功, userId={}, count={}", userId, response.getTotal());
        return Result.success(response);
    }

    @PostMapping("/users/me/favorite-jobs")
    public Result<?> addFavoriteJob(@RequestBody Map<String, String> requestBody,
                                    HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        String jobId = requestBody == null ? null : requestBody.get("jobId");
        log.info("[接口访问] POST /users/me/favorite-jobs, userId={}, authenticated={}, jobId={}",
                userId, UserContext.isAuthenticated(request), jobId);
        if (favoriteJobService.add(userId, jobId)) {
            log.info("[接口完成] 收藏岗位成功, userId={}, jobId={}", userId, jobId);
            return Result.success(null, "收藏成功");
        }
        log.info("[接口完成] 收藏岗位失败, userId={}, jobId={}", userId, jobId);
        return Result.error(500, "添加失败");
    }

    @DeleteMapping("/users/me/favorite-jobs/{jobId}")
    public Result<?> deleteFavoriteJob(@PathVariable String jobId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] DELETE /users/me/favorite-jobs/{jobId}, userId={}, authenticated={}, jobId={}",
                userId, UserContext.isAuthenticated(request), jobId);
        if (favoriteJobService.remove(userId, jobId)) {
            log.info("[接口完成] 取消收藏成功, userId={}, jobId={}", userId, jobId);
            return Result.success(null, "取消收藏成功");
        }
        log.info("[接口完成] 取消收藏失败, userId={}, jobId={}", userId, jobId);
        return Result.error(500, "取消收藏失败");
    }
}
