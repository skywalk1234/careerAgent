package group.careerservice.controller;/* I love coding */

import group.careerservice.client.ProfileClient;
import group.careerservice.domain.dto.AnalyzeJobDTO;
import group.careerservice.domain.dto.JobBrief;
import group.careerservice.domain.dto.MatchJob;
import group.careerservice.domain.po.AnalyzeJobPo;
import group.careerservice.domain.po.MatchJobPo;
import group.careerservice.domain.response.MatchFilterRes;
import group.utils.UserContext;
import group.vo.AnalyzeVO;
import group.vo.MatchFilter;
import group.careerservice.service.JobAnalyze.JobAnalyzeService;
import group.careerservice.service.JobAnalyze.ProcessMatchFilterService;
import group.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class JobAnalyzeController {
    private final ProcessMatchFilterService processMatchFilterService;
    private final JobAnalyzeService jobAnalyzeService;
    private final RabbitTemplate rabbitTemplate;
    private final ProfileClient profileClient;
//    查询推荐岗位结果
    @GetMapping("/users/me/match/recommendations")
    public Result queryMatchRecommendations(@RequestParam("topN") Integer topN) {
        log.info("开始查询用户匹配的岗位推荐");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        MatchJobPo matchJobpo = processMatchFilterService.queryRecommendations(userId, topN);
        MatchJob matchJob = matchJobpo.getMatchResult();
        Map<String, Object> response = new HashMap<>();
        if (matchJob != null) {

            response.put("recommendationStatus", matchJob.getOtherRecommendations());
            response.put("recommendedAt", matchJobpo.getCreatedAt());
            response.put("bestMatch", matchJob.getBestMatch());
            response.put("otherRecommendations", matchJob.getOtherRecommendations());
            log.info("查询成功，返回岗位推荐列表");
            return Result.success(response);
        }
        log.info("岗位推荐表还在分析中");
        // 创建Map并填入所有字段
        Map<String, Object> nullMap = new HashMap<>();
// 填入固定值

        nullMap.put("recommendationStatus", "processing");
        nullMap.put("reason", "人岗匹配分析中，请稍候。");
        nullMap.put("pollAfterMs", 1500);
        nullMap.put("bestMatch", null);
        nullMap.put("otherRecommendations", new ArrayList<>());
        return Result.success(nullMap);
    }
//  细化推荐范围并重新推荐
    @PostMapping("/users/me/match/recommendations/refine")
    public Result queryMatchFilters(@RequestBody MatchFilter filters) throws Exception{
        log.info("开始根据细化条件召唤ai进行推荐");

        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", userId);
        msg.put("filters", filters);
        rabbitTemplate.convertAndSend("recommend", msg);

//        String s = processMatchFilterService.readyToAI(filters, "111");
        Result profile = profileClient.getProfile(userId);
        if (profile.getCode() == 201){
            log.info("画像不存在");
            return Result.error(409, "请先完成画像分析后再细化匹配范围");
        }
        log.info("画像存在，正在分析");
        MatchFilterRes matchFilterRes = new MatchFilterRes(filters.getScope().getPreferredJobKeywords(), filters.getScope().getCityIntents(), filters.getScope().getSalaryRange(), filters.getScope().getIncludeSimilarJobs());
        Map<String, Object> response = new HashMap<>();
        response.put("recommendationStatus", "processing");
        response.put("pollAfterMs", 1500);
        response.put("topN", filters.getTopN());
        response.put("scope", matchFilterRes);
        return Result.success(response);

    }

//    人岗多维度深度分析
    @PostMapping("/users/me/match/analyze")
    public Result AnalyzeJobs(@RequestBody AnalyzeVO analyzeVO) throws Exception{
        log.info("开始进行岗位匹配分析");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        AnalyzeJobPo po = jobAnalyzeService.analyze(userId, analyzeVO);
        AnalyzeJobDTO analyze = po.getAnalysis();
        if (analyze != null) {
            log.info("分析成功，返回分析结果");
            Map<String, Object> map = new HashMap<>();
            map.put("recordId", po.getId().toString());
            //这个就是数据库的主键
            map.put("source", "favorite");
            map.put("sourceMeta", Map.of("from", "favorite-panel"));
            map.put("job", analyze.getJob());
            map.put("analysis", analyze.getAnalysis());
            map.put("createdAt", LocalDateTime.now().toString());
            map.put("updatedAt", LocalDateTime.now().toString());
            map.put("pinned", false);
            map.put("overwritten", true);
            map.put("removedRecordId", "mr_09999");
            return Result.success(map);
        }

        return Result.error(409, "服务器内部错误");
    }


    //获取分析历史的详细信息
    @GetMapping("/users/me/match/history/{recordId}")
    public Result queryAnalyzeDetailHistory(@PathVariable("recordId") String recordId) {
        log.info("开始查询用户匹配历史");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        AnalyzeJobPo po = jobAnalyzeService.getDetail(recordId);
        AnalyzeJobDTO analyze = po.getAnalysis();
        if (analyze != null) {
            log.info("分析成功，返回分析结果");
            Map<String, Object> map = new HashMap<>();
            map.put("recordId", po.getId().toString());
            //这个就是数据库的主键
            map.put("source", "favorite");
            map.put("sourceMeta", Map.of("from", "favorite-panel"));
            map.put("job", analyze.getJob());
            map.put("analysis", analyze.getAnalysis());
            map.put("createdAt", LocalDateTime.now().toString());
            map.put("updatedAt", LocalDateTime.now().toString());
            map.put("pinned", false);
            map.put("overwritten", true);
            map.put("removedRecordId", "mr_09999");
            return Result.success(map);
        }
        return null;

    }

    //获取分析历史列表，简短消息
    @GetMapping("/users/me/match/history")
    public Result queryBriefList(){

        log.info("开始查询用户匹配历史列表");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        List<JobBrief> historyList = jobAnalyzeService.getHistoryList(userId);
        Map<String, Object> result = new HashMap<>();
        result.put("total", 3);
        result.put("pinnedRecordId", "mr_10001");
        result.put("list", historyList);
        return Result.success(result);

    }


}
