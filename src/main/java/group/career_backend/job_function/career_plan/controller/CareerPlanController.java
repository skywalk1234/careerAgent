package group.career_backend.job_function.career_plan.controller;

import group.career_backend.common.Result;
import group.career_backend.job_function.career_plan.domain.dto.CareerPlanCreateDTO;
import group.career_backend.job_function.career_plan.domain.dto.CareerPlanUpdateDTO;
import group.career_backend.job_function.career_plan.domain.po.CareerPlan;
import group.career_backend.job_function.career_plan.service.CareerPlanService;
import group.career_backend.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/users/me/plans")
public class CareerPlanController {

    private final CareerPlanService careerPlanService;

    @PostMapping
    public Result<?> create(@RequestBody CareerPlanCreateDTO dto, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] POST /users/me/plans, userId={}", userId);
        String title = dto.getTitle();
        String content = dto.getContent();
        if (title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            log.info("[接口完成] 创建行动方案参数校验失败, userId={}", userId);
            return Result.error(400, "title 和 content 不能为空");
        }

        CareerPlan plan = careerPlanService.createPlan(userId, title.trim(), content, dto.getSessionId());
        Map<String, Object> data = new HashMap<>();
        data.put("planId", plan.getId());
        data.put("title", plan.getTitle());
        data.put("status", plan.getStatus());
        log.info("[接口完成] 创建行动方案成功, userId={}, planId={}", userId, plan.getId());
        return Result.success(data, "行动方案已保存");
    }

    @GetMapping
    public Result<?> list(HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/plans, userId={}", userId);
        List<CareerPlan> plans = careerPlanService.listPlans(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (CareerPlan plan : plans) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", plan.getId());
            item.put("title", plan.getTitle());
            item.put("status", plan.getStatus());
            item.put("createdAt", plan.getCreatedAt() == null ? null : plan.getCreatedAt().toString());
            items.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", items.size());
        data.put("list", items);
        log.info("[接口完成] 查询行动方案列表成功, userId={}, count={}", userId, items.size());
        return Result.success(data);
    }

    @GetMapping("/{planId}")
    public Result<?> detail(@PathVariable Long planId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/plans/{planId}, userId={}, planId={}", userId, planId);
        CareerPlan plan = careerPlanService.getDetail(userId, planId);
        if (plan == null) {
            log.info("[接口完成] 行动方案不存在或无权访问, userId={}, planId={}", userId, planId);
            return Result.error(404, "方案不存在或无权访问");
        }
        log.info("[接口完成] 查询行动方案详情成功, userId={}, planId={}", userId, planId);
        return Result.success(planDetailMap(plan));
    }

    @PutMapping("/{planId}")
    public Result<?> update(@PathVariable Long planId, @RequestBody CareerPlanUpdateDTO dto,
                            HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] PUT /users/me/plans/{planId}, userId={}, planId={}", userId, planId);
        String content = dto.getContent();
        if (content == null || content.trim().isEmpty()) {
            log.info("[接口完成] 更新行动方案参数校验失败, userId={}, planId={}", userId, planId);
            return Result.error(400, "content 不能为空");
        }

        CareerPlan plan = careerPlanService.updateContent(userId, planId, content.trim());
        if (plan == null) {
            log.info("[接口完成] 行动方案不存在或无权访问, userId={}, planId={}", userId, planId);
            return Result.error(404, "方案不存在或无权访问");
        }
        log.info("[接口完成] 更新行动方案成功, userId={}, planId={}", userId, planId);
        return Result.success(planDetailMap(plan), "方案已更新");
    }

    @DeleteMapping("/{planId}")
    public Result<?> delete(@PathVariable Long planId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] DELETE /users/me/plans/{planId}, userId={}, planId={}", userId, planId);
        if (!careerPlanService.deletePlan(userId, planId)) {
            log.info("[接口完成] 行动方案不存在或无权访问, userId={}, planId={}", userId, planId);
            return Result.error(404, "方案不存在或无权访问");
        }
        log.info("[接口完成] 删除行动方案成功, userId={}, planId={}", userId, planId);
        return Result.success(null, "方案已删除");
    }

    private Map<String, Object> planDetailMap(CareerPlan plan) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", plan.getId());
        data.put("title", plan.getTitle());
        data.put("status", plan.getStatus());
        data.put("content", plan.getContent());
        data.put("supersedesPlanId", plan.getSupersedesPlanId());
        data.put("createdAt", plan.getCreatedAt() == null ? null : plan.getCreatedAt().toString());
        return data;
    }
}
