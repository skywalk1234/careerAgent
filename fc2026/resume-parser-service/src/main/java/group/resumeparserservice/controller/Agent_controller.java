package group.resumeparserservice.controller;/* I love coding */

import group.common.Result;
import group.resumeparserservice.domain.vo.ApprovalRequest;
import group.resumeparserservice.domain.vo.ApprovalResponse;
import group.resumeparserservice.domain.vo.AssistantMessage;
import group.resumeparserservice.domain.vo.ChatVO;
import group.resumeparserservice.domain.vo.StreamConfig;
import group.resumeparserservice.domain.vo.UserMessage;
import group.resumeparserservice.service.AI_recommend;
import group.resumeparserservice.service.AI_route_planning;
import group.resumeparserservice.service.AssistantService;
import group.tool.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class Agent_controller {

    private final AssistantService assistantService;
    private final AI_recommend ai_recommend;
    private final AI_route_planning ai_route_planning;

//    SSE流式连接
    @PostMapping("/users/me/home/assistant/sessions/{sessionId}/messages")
    public Result startChatting(@PathVariable String sessionId, @RequestBody ChatVO chatVO) {
        String userId = "111";// 从token中解析
        log.info("接收到对话：sessionId: {}, userId: {}", sessionId, userId);
        String messageId = IdGenerator.generateShortId();
        assistantService.sendMessage(sessionId, chatVO, messageId, userId);
        UserMessage userMessage = UserMessage.builder()
                .messageId(messageId)
                .role("user")
                .content(chatVO.getContent())
                .status("succeeded")
                .createdAt(LocalDateTime.now())
                .build();
        AssistantMessage assistantMessage = AssistantMessage.builder()
                .messageId(messageId)
                .agentTrace(new AssistantMessage.AgentTrace("v1","agent-lite","processing", "", new ArrayList<>(), new ArrayList<>()))
                .role("user")
                .actions(new ArrayList<>())
                .content("")
                .status("succeeded")

                .createdAt(LocalDateTime.now())
                .build();
        String urlTemplate = "/users/me/home/assistant/sessions/%s/messages/%s/stream";
        String url = String.format(urlTemplate, sessionId, messageId);
//        到时候前端将用这个URL进行sse连接
        StreamConfig streamConfig = StreamConfig.builder()
                .protocol("sse")
                .url(url)
                .build();
        Map<String, Object> map = new HashMap<>();
        map.put("sessionId", sessionId);
        map.put("userMessage", userMessage);
        map.put("assistantMessage", assistantMessage);
        map.put("stream", streamConfig);

        return Result.success(map, "消息已创建，请使用SSE接收增量回复");
    }

    @GetMapping("/users/me/home/assistant/sessions/{sessionId}/messages/{messageId}/stream")
    public SseEmitter stream(@PathVariable String sessionId, @PathVariable String messageId, @RequestParam("token") String token) {
        log.info("开始 sse 连接：sessionId: {}, messageId: {}", sessionId, messageId);
        SseEmitter sseEmitter = assistantService.startStreaming(sessionId, messageId);
        return sseEmitter;
    }

//    推荐岗位大类
    @PostMapping("/jobs/recommend")
    public String recommendJobs(@RequestBody String request_json){
        log.info("接收到 ai 推荐的请求");
        String res_json = ai_recommend.recommendCategory(request_json);
        return res_json;
    }


//    根据大类推荐具体岗位
    @PostMapping("/jobs/recommend/specific")
    public String recommendSpecificJobs(@RequestBody String request_json){
        log.info("接收到ai推荐的具体岗位请求");
        String res_json = ai_recommend.recommendSpecificJob(request_json);
        log.info("ai推荐结果：{}", res_json);
        return res_json;
    }
//    自动推荐岗位路径
    @PostMapping("/jobs/route/auto-plan")
    public String routePlanning(@RequestBody Map<String, Object> request){
        List<String> categoryList = (List<String>) request.get("categoryList");
        String profile_json = (String) request.get("profile");
        String res_json = ai_route_planning.routePlanning(categoryList, profile_json);
        return res_json;
    }

//    处理审批请求
    @PostMapping("/users/me/home/assistant/message/{messageId}/approvals")
    public Result processApproval(@PathVariable String messageId, @RequestBody ApprovalRequest request) {
        log.info("处理审批请求：taskId: {}, approvalId: {}", messageId, request.getApprovalId());

        ApprovalResponse response = assistantService.processApproval(messageId, request);

        return Result.success(response, "审批已处理");
    }

//    任务控制接口（暂停/恢复/取消/重试）
    @PostMapping("/users/me/home/assistant/tasks/{taskId}/control")
    public Result controlTask(@PathVariable String taskId, @RequestBody Map<String, String> request) {
        String action = request.get("action");

        log.info("任务控制请求：taskId={}, action={}", taskId, action);

        if (action == null || action.isEmpty()) {
            return Result.error(400, "action 参数不能为空");
        }

        if (!java.util.Arrays.asList("pause", "resume", "cancel", "retry").contains(action)) {
            return Result.error(400, "不支持的控制动作：" + action);
        }

        assistantService.controlTask(taskId, action);

        return Result.success(Map.of(
            "taskId", taskId,
            "action", action,
            "status", "running"
        ), "任务控制指令已发送");
    }


}
