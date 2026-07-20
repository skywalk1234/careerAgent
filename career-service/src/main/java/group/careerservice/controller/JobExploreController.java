package group.careerservice.controller;/* I love coding */

import group.careerservice.domain.po.FavoriteJob;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.response.FavoriteRes;
import group.careerservice.domain.response.JobFilterRes;
import group.careerservice.domain.response.JobGraphRes;
import group.careerservice.domain.response.JobRelationRes;
import group.careerservice.domain.vo.JobsFilter;
import group.careerservice.service.JobExploration.AddFavoriteJobService;
import group.careerservice.service.JobExploration.QueryFavoriteJobService;
import group.careerservice.service.JobExploration.QueryJobGraphService;
import group.careerservice.service.JobExploration.QueryJobRelationService;
import group.careerservice.service.JobExploration.SaveJobService;
import group.careerservice.tools.FavoriteTrans;
import group.common.Result;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class JobExploreController {
    private final SaveJobService saveJobService;
    private final QueryFavoriteJobService queryFavoriteJobService;
    private final AddFavoriteJobService addFavoriteJobService;
    private final FavoriteTrans favoriteTrans;
    private final QueryJobRelationService queryJobRelationService;
    private final QueryJobGraphService queryJobGraphService;
    @GetMapping("/jobs/{jobId}")
    public Result queryJobById(@PathVariable String jobId) {
        log.info("开始查询岗位详细信息，jobId: {}", jobId);
        JobDocument jobDocument = saveJobService.queryJobById(jobId);
        if (jobDocument != null) {
            log.info("查询成功，返回岗位详细信息");
            return Result.success(jobDocument);
        }

        return Result.error(500, "查询失败");
    }

    @GetMapping("/jobs/filters")
    public Result queryJobFilters() {
        log.info("开始查询岗位筛选条件");
        return Result.success(JobFilterRes.getDefaultOptions());
    }

    //根据过滤条件查询岗位
    @PostMapping("/jobs/search")
    public Result queryJobsByFilter(@RequestBody JobsFilter filters) {
        log.info("开始查询所有岗位信息");
        Page<JobDocument> jobs = saveJobService.queryJobsByFilter(filters);
        log.info("查询成功，返回岗位列表，数量：{}", jobs.getTotalElements());

        Map<String, Object> response = new HashMap<>();
        response.put("total", jobs.getTotalElements());
        response.put("page", filters.getPage() != null ? filters.getPage() : 1);
        response.put("pageSize", filters.getPageSize() != null ? filters.getPageSize() : 20);
        response.put("list", jobs.getContent());

        return Result.success(response);
    }

    @GetMapping("/users/me/favorite-jobs")
    public Result queryFavoriteJobs() {
        log.info("开始查询用户收藏的岗位");
        Long userIdLong = UserContext.getUser();
        if (userIdLong == null) {
            userIdLong = 111L;
        }
        List<FavoriteJob> records = queryFavoriteJobService.queryFavoriteJobByUserId(userIdLong);
        FavoriteRes response = favoriteTrans.trans(records);
        log.info("查询成功，返回收藏的岗位列表，数量：{}", records.size());
        return Result.success(response);
    }

    @PostMapping("/users/me/favorite-jobs")
    public Result addFavoriteJob(@RequestBody Map<String, String> requestBody) {
        String jobId = requestBody.get("jobId");
        log.info("开始添加用户收藏的岗位，jobId: {}", jobId);
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        Integer i = addFavoriteJobService.addFavoriteJob(jobId, userId);
        if (i > 0) {
            log.info("添加成功");
            return Result.error(200, "收藏成功");
        }
        return Result.error(500, "添加失败");
    }

    @DeleteMapping("/users/me/favorite-jobs/{jobId}")
    public Result deleteFavoriteJob(@PathVariable("jobId") String jobId) {

        Long userIdLong = UserContext.getUser();
        if (userIdLong == null) {
            userIdLong = 111L;
        }
        Integer i = addFavoriteJobService.deleteFavoriteJob(jobId, userIdLong);
        if (i > 0) {
            log.info("添加成功");
            return Result.error(200, "取消收藏成功");
        }
        return Result.error(500, "添加失败");
    }

//    查询岗位类别关系图谱（晋升+换岗）pass
    @GetMapping("/jobs/graph")
    public Result queryJobGraph(@RequestParam String categoryName) {
        log.info("接收到查询岗位图谱的请求，categoryName: {}", categoryName);
        JobGraphRes jobGraph = queryJobGraphService.queryJobGraph(categoryName);
        return Result.success(jobGraph, "OK");
    }





}
