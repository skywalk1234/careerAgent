package group.careerservice.controller;/* I love coding */

import com.fasterxml.jackson.core.JsonProcessingException;
import group.careerservice.domain.dto.DraftInfoDTO;
import group.careerservice.domain.dto.LatestRouteDTO;
import group.careerservice.domain.dto.PathListDTO;
import group.careerservice.domain.response.DeepEvalStatusRes;
import group.careerservice.domain.response.RealTimeEvalRes;
import group.careerservice.domain.vo.DeepEvalVO;
import group.careerservice.domain.vo.SimpleRouteVO;
import group.careerservice.service.Route.GetDeepEvalService;
import group.careerservice.service.Route.JobRouteService;
import group.careerservice.service.Route.RouteListService;
import group.tool.IdGenerator;
import group.common.Result;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping
public class RouteController {
    private final JobRouteService jobRouteService;
    private final RabbitTemplate rabbitTemplate;
    private final GetDeepEvalService deepEvalService;
    private final RouteListService routeListService;
//    ai自动规划职业路径 pass
    @PostMapping("/users/me/career-path/auto-plan")
    public Result autoPlan(@RequestBody Map<String, String> requestBody) {
        log.info("接收到自动规划路径请求");
        String jobId = requestBody.get("targetJobId");
        log.info("目标岗位id为：{}", jobId);
        log.info("开始规划路径");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";

        Map<String, Object> resultMap = new HashMap<>();
        String autoPlanJobId = IdGenerator.generateShortId();

        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", userId);
        msg.put("autoPlanJobId", autoPlanJobId);
        msg.put("jobId", jobId);
        msg.put("mode", requestBody.get("mode"));
//        由JobRouteService中的autoPlanRoute进行监听
        rabbitTemplate.convertAndSend("route_planning", msg);

// 添加各个字段
        resultMap.put("autoPlanJobId", autoPlanJobId);
        resultMap.put("status", "processing");
        resultMap.put("pollAfterMs", 2000);
        return Result.success(resultMap, "职业路径自动规划任务已创建");
    }
// 轻评估（实时评估）pass
    @PostMapping("/users/me/career-path/realtime-evaluate")
    public Result realtimeEvaluate(@RequestBody SimpleRouteVO requestBody) throws JsonProcessingException {
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        RealTimeEvalRes  res = jobRouteService.realtimeEvaluate(userId, requestBody);
        return Result.success(res);
    }
// 创建深评估任务 pass
    @PostMapping("/users/me/career-path/save")
    public Result DeepEval(@RequestBody DeepEvalVO requestBody){
        log.info("接收到创建深评估任务请求");
        String saveJobId = IdGenerator.generateShortId();//用雪花算法生成一个
        Long userIdLong = UserContext.getUser();
        System.out.println("用户:"+userIdLong);
        String userId = userIdLong != null ? userIdLong.toString() : "111";
    //通过消息队列异步执行
        Map<String, Object> msg = new HashMap<>();
        msg.put("userId", userId);
        msg.put("saveJobId", saveJobId);
        msg.put("requestBody", requestBody);
//    由JobRouteService中的deepEvaluate进行监听
        rabbitTemplate.convertAndSend("deep_eval", msg);
        
        
        Map<String, Object> statusMap = new HashMap<>();
        statusMap.put("saveJobId", saveJobId);
        statusMap.put("status", "processing");
        statusMap.put("pollAfterMs", 2000);
        return Result.success(statusMap, "保存任务已创建");
    }
//查询深度评估任务状态 pass
    @GetMapping("/users/me/career-path/save-jobs/{saveJobId}")
    public Result getDeepEvalStatus(@PathVariable String saveJobId){

        log.info("接收到获取深评估任务状态请求");
        DeepEvalStatusRes evalStatus = deepEvalService.getEvalStatus(saveJobId);
        if(evalStatus == null){
            Map<String, Object> notFoundMap = new HashMap<>();
            notFoundMap.put("saveJobId", saveJobId);
            notFoundMap.put("status", "processing");
            notFoundMap.put("progress", 99);
            notFoundMap.put("pollAfterMs", 2000);
            return Result.success(notFoundMap, "处理中");
        }

        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("saveJobId", saveJobId);
        resultMap.put("status", "succeeded");
        resultMap.put("result", new HashMap<String, Object>(){{
            put("savedPath", evalStatus);
        }});
        return Result.success(resultMap, "保存完成");
    }
//获取最新的职业路径id pass
    @GetMapping("/users/me/career-path/latest")
    public Result getLatestCareerPathId(){
        log.info("接收到获取最新职业路径id请求");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        LatestRouteDTO latestRoute = routeListService.getLatestRoute(userId);
        return Result.success(latestRoute);
    }

//    获取保存路径列表 pass
    @GetMapping("/users/me/career-path/saved-list")
    public Result getSavedCareerPathList(){
        log.info("接收到获取保存路径列表请求");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        List<PathListDTO> pathList = routeListService.getPathList(userId);
        Map<String, Object> resultMap = new HashMap<>();
        resultMap.put("total", pathList.size());
        resultMap.put("list", pathList);
        return Result.success(resultMap);
    }

//     获取职业路径详情 pass
    @GetMapping("/users/me/career-path/detail")
    public Result getCareerPathDetail(
            @RequestParam(value = "pathId", required = false) String pathId,
            @RequestParam(value = "draftId", required = false) String draftId
    ){
        log.info("接收到获取职业路径详情请求");
        if (pathId != null) {
            log.info("获取指定职业路径详情");
            DeepEvalStatusRes res = deepEvalService.getEvalByPathId(pathId);
            Map<String, Object> map = new HashMap<>();
            map.put("source", "save");
            map.put("savedPath", res);
            return Result.success(map, "ok");

        } else if (draftId != null) {
            log.info("获取草稿职业路径详情");
            DraftInfoDTO draftInfo = jobRouteService.getDraftInfo(draftId);
            Map<String, Object> map = new HashMap<>();
            map.put("source", "saved");
            map.put("savedPath", draftInfo);
            return Result.success(map, "ok");
        } else {
            log.info("两个参数都为空");
            return Result.error(200, "两个参数都为空");
        }
    }

    @DeleteMapping("/users/me/career-path/saved-list/{pathId}")
    public Result deleteCareerPath(@PathVariable String pathId) {
        log.info("接收到删除职业路径请求");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        Integer res = routeListService.deletePath(userId, pathId);
        if (res>0) {
            return Result.success(200, "删除保存的路径成功");
        } else {
            return Result.error(200, "删除保存路径失败失败");
        }
    }


}
