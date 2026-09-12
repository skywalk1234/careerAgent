package group.career_backend.job_function.career_plan.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import group.career_backend.job_function.career_plan.domain.po.CareerPlan;
import group.career_backend.job_function.career_plan.mapper.CareerPlanMapper;
import group.career_backend.job_function.career_plan.service.CareerPlanService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class CareerPlanServiceImpl implements CareerPlanService {

    private final CareerPlanMapper careerPlanMapper;

    @Override
    @Transactional
    public CareerPlan createPlan(Long userId, String title, String content, String sessionId) {
        log.info("[业务处理] 开始创建行动方案, userId={}", userId);
        CareerPlan previousActive = careerPlanMapper.selectOne(new LambdaQueryWrapper<CareerPlan>()
                .eq(CareerPlan::getUserId, userId)
                .eq(CareerPlan::getStatus, "active")
                .orderByDesc(CareerPlan::getCreatedAt)
                .orderByDesc(CareerPlan::getId)
                .last("LIMIT 1"));

        CareerPlan newPlan = new CareerPlan();
        newPlan.setUserId(userId);
        newPlan.setSessionId(sessionId);
        newPlan.setStatus("active");
        newPlan.setTitle(title);
        newPlan.setContent(content);
        newPlan.setCreatedAt(LocalDateTime.now());
        if (previousActive != null) {
            newPlan.setSupersedesPlanId(previousActive.getId());
        }
        careerPlanMapper.insert(newPlan);

        if (previousActive != null) {
            careerPlanMapper.update(null, new LambdaUpdateWrapper<CareerPlan>()
                    .eq(CareerPlan::getId, previousActive.getId())
                    .eq(CareerPlan::getUserId, userId)
                    .eq(CareerPlan::getStatus, "active")
                    .set(CareerPlan::getStatus, "archived"));
        }
        log.info("[业务处理] 行动方案创建完成, userId={}, planId={}, supersedesPlanId={}",
                userId, newPlan.getId(), newPlan.getSupersedesPlanId());
        return newPlan;
    }

    @Override
    public List<CareerPlan> listPlans(Long userId) {
        log.info("[业务处理] 开始查询行动方案列表, userId={}", userId);
        List<CareerPlan> plans = careerPlanMapper.selectList(new LambdaQueryWrapper<CareerPlan>()
                .select(CareerPlan::getId, CareerPlan::getTitle, CareerPlan::getStatus, CareerPlan::getCreatedAt)
                .eq(CareerPlan::getUserId, userId)
                .orderByDesc(CareerPlan::getCreatedAt)
                .orderByDesc(CareerPlan::getId));
        log.info("[业务处理] 行动方案列表查询完成, userId={}, count={}", userId, plans.size());
        return plans;
    }

    @Override
    public CareerPlan getDetail(Long userId, Long planId) {
        log.info("[业务处理] 开始查询行动方案详情, userId={}, planId={}", userId, planId);
        CareerPlan plan = careerPlanMapper.selectOne(planOwnerQuery(userId, planId));
        log.info("[业务处理] 行动方案详情查询完成, userId={}, planId={}, found={}",
                userId, planId, plan != null);
        return plan;
    }

    @Override
    public CareerPlan updateContent(Long userId, Long planId, String content) {
        log.info("[业务处理] 开始更新行动方案, userId={}, planId={}", userId, planId);
        CareerPlan plan = getDetail(userId, planId);
        if (plan == null) {
            log.info("[业务处理] 更新行动方案未命中, userId={}, planId={}", userId, planId);
            return null;
        }

        String newTitle = extractTitleFromContent(content);
        LambdaUpdateWrapper<CareerPlan> updateWrapper = new LambdaUpdateWrapper<CareerPlan>()
                .eq(CareerPlan::getId, planId)
                .eq(CareerPlan::getUserId, userId)
                .set(CareerPlan::getContent, content);
        if (newTitle != null) {
            updateWrapper.set(CareerPlan::getTitle, newTitle);
        }
        if (careerPlanMapper.update(null, updateWrapper) == 0) {
            log.info("[业务处理] 更新行动方案未命中, userId={}, planId={}", userId, planId);
            return null;
        }

        plan.setContent(content);
        if (newTitle != null) {
            plan.setTitle(newTitle);
        }
        log.info("[业务处理] 行动方案更新完成, userId={}, planId={}", userId, planId);
        return plan;
    }

    @Override
    @Transactional
    public boolean deletePlan(Long userId, Long planId) {
        log.info("[业务处理] 开始删除行动方案, userId={}, planId={}", userId, planId);
        CareerPlan plan = getDetail(userId, planId);
        if (plan == null) {
            log.info("[业务处理] 删除行动方案未命中, userId={}, planId={}", userId, planId);
            return false;
        }

        int deleted = careerPlanMapper.delete(planOwnerQuery(userId, planId));
        if (deleted == 0) {
            log.info("[业务处理] 删除行动方案未命中, userId={}, planId={}", userId, planId);
            return false;
        }

        if ("active".equals(plan.getStatus())) {
            CareerPlan latestArchived = careerPlanMapper.selectOne(new LambdaQueryWrapper<CareerPlan>()
                    .eq(CareerPlan::getUserId, userId)
                    .eq(CareerPlan::getStatus, "archived")
                    .orderByDesc(CareerPlan::getCreatedAt)
                    .orderByDesc(CareerPlan::getId)
                    .last("LIMIT 1"));
            if (latestArchived != null) {
                careerPlanMapper.update(null, new LambdaUpdateWrapper<CareerPlan>()
                        .eq(CareerPlan::getId, latestArchived.getId())
                        .eq(CareerPlan::getUserId, userId)
                        .eq(CareerPlan::getStatus, "archived")
                        .set(CareerPlan::getStatus, "active"));
                log.info("[业务处理] 已提升归档行动方案, userId={}, planId={}",
                        userId, latestArchived.getId());
            }
        }
        log.info("[业务处理] 行动方案删除完成, userId={}, planId={}", userId, planId);
        return true;
    }

    private LambdaQueryWrapper<CareerPlan> planOwnerQuery(Long userId, Long planId) {
        return new LambdaQueryWrapper<CareerPlan>()
                .eq(CareerPlan::getId, planId)
                .eq(CareerPlan::getUserId, userId);
    }

    private String extractTitleFromContent(String content) {
        for (String rawLine : content.split("\n")) {
            String line = rawLine.trim();
            if (line.startsWith("#")) {
                String title = line.replaceFirst("^#{1,6}\\s*", "").trim();
                if (!title.isEmpty()) {
                    return title.length() <= 200 ? title : title.substring(0, 200);
                }
            }
        }
        return null;
    }
}
