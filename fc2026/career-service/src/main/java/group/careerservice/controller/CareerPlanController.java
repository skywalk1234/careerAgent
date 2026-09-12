package group.careerservice.controller;/* I love coding */

import group.careerservice.domain.dto.CareerPlanCreateDTO;
import group.careerservice.domain.dto.CareerPlanUpdateDTO;
import group.careerservice.domain.po.CareerPlan;
import group.careerservice.service.CareerPlan.CareerPlanService;
import group.common.Result;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 职业规划专家 · 行动方案的存储与查询接口（前端经网关 /api 访问，Python 专家经网关 POST 落库）。
 * 网关 career-user 路由（Path=/users/me/** → career-service）已覆盖本前缀，无需新增路由。
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/users/me/plans")
public class CareerPlanController {

    private final CareerPlanService careerPlanService;

    /** 创建方案：Python 专家生成后调用；同用户旧的 active 方案自动置 archived */
    @PostMapping
    public Result create(@RequestBody CareerPlanCreateDTO dto) {
        String title = dto.getTitle();
        String content = dto.getContent();
        if (title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            return Result.error(400, "title 和 content 不能为空");
        }
        Long userId = getUserId();
        CareerPlan plan = careerPlanService.createPlan(userId, title.trim(), content, dto.getSessionId());
        Map<String, Object> data = new HashMap<>();
        data.put("planId", plan.getId());
        data.put("title", plan.getTitle());
        data.put("status", plan.getStatus());
        return Result.success(data, "行动方案已保存");
    }

    /** 方案列表（不含 content，按时间倒序；当前方案 = 最新一条 active） */
    @GetMapping
    public Result list() {
        Long userId = getUserId();
        List<CareerPlan> plans = careerPlanService.listPlans(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CareerPlan plan : plans) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", plan.getId());
            item.put("title", plan.getTitle());
            item.put("status", plan.getStatus());
            item.put("createdAt", plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
            items.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", items.size());
        data.put("list", items);
        return Result.success(data);
    }

    /** 方案详情（含 content 全文）；校验归属，非本人不可见 */
    @GetMapping("/{planId}")
    public Result detail(@PathVariable Long planId) {
        Long userId = getUserId();
        CareerPlan plan = careerPlanService.getDetail(userId, planId);
        if (plan == null) {
            return Result.error(404, "方案不存在或无权访问");
        }
        return Result.success(planDetailMap(plan));
    }

    /** 修改方案正文（渲染后编辑保存回 markdown 全文）；校验归属，非本人 404 */
    @PutMapping("/{planId}")
    public Result update(@PathVariable Long planId, @RequestBody CareerPlanUpdateDTO dto) {
        String content = dto.getContent();
        if (content == null || content.trim().isEmpty()) {
            return Result.error(400, "content 不能为空");
        }
        Long userId = getUserId();
        CareerPlan plan = careerPlanService.updateContent(userId, planId, content.trim());
        if (plan == null) {
            return Result.error(404, "方案不存在或无权访问");
        }
        return Result.success(planDetailMap(plan), "方案已更新");
    }

    /** 删除方案；若删除的是当前 active，服务端会把最新一条 archived 提升为 active */
    @DeleteMapping("/{planId}")
    public Result delete(@PathVariable Long planId) {
        Long userId = getUserId();
        boolean deleted = careerPlanService.deletePlan(userId, planId);
        if (!deleted) {
            return Result.error(404, "方案不存在或无权访问");
        }
        return Result.success(null, "方案已删除");
    }

    /** 方案详情字段（与 detail 返回结构一致） */
    private Map<String, Object> planDetailMap(CareerPlan plan) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", plan.getId());
        data.put("title", plan.getTitle());
        data.put("status", plan.getStatus());
        data.put("content", plan.getContent());
        data.put("supersedesPlanId", plan.getSupersedesPlanId());
        data.put("createdAt", plan.getCreatedAt() != null ? plan.getCreatedAt().toString() : null);
        return data;
    }

    /** 当前登录用户 id；网关已做 JWT 校验，理论上必不为空，兜底对齐 career-service 其它接口的 "111" 写法 */
    private Long getUserId() {
        Long userId = UserContext.getUser();
        return userId != null ? userId : 111L;
    }
}
