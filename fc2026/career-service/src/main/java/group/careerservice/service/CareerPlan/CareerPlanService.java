package group.careerservice.service.CareerPlan;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.CareerPlan;
import group.careerservice.mapper.CareerPlanMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 行动方案的存储与查询（表 user_career_plans）。
 * 新方案入库时把该用户上一条 active 置 archived 并回填 supersedes_plan_id 版本链，
 * 保证「同 user 最新一条 active = 当前方案」，历史快照不物理删（可回看成长轨迹）。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CareerPlanService {

    private final CareerPlanMapper careerPlanMapper;

    /** 创建行动方案：插入一条 active，并把该用户此前最新的 active 置 archived（原子） */
    @Transactional
    public CareerPlan createPlan(Long userId, String title, String content, String sessionId) {
        CareerPlan newPlan = new CareerPlan();
        newPlan.setUserId(userId);
        newPlan.setStatus("active");
        newPlan.setTitle(title);
        newPlan.setContent(content);
        newPlan.setSessionId(sessionId);
        newPlan.setCreatedAt(LocalDateTime.now());

        // 找该用户当前的 active 方案（最新一条），用于版本链；LIMIT 1 保证 selectOne 不因多条抛异常
        QueryWrapper<CareerPlan> activeWrapper = new QueryWrapper<>();
        activeWrapper.eq("user_id", userId)
                .eq("status", "active")
                .orderByDesc("created_at")
                .orderByDesc("id")
                .last("LIMIT 1");
        CareerPlan prevActive = careerPlanMapper.selectOne(activeWrapper);

        if (prevActive != null) {
            newPlan.setSupersedesPlanId(prevActive.getId());
        }
        careerPlanMapper.insert(newPlan);

        if (prevActive != null) {
            prevActive.setStatus("archived");
            careerPlanMapper.updateById(prevActive);
        }

        log.info("创建行动方案成功 userId={}, planId={}, supersedes={}", userId, newPlan.getId(),
                newPlan.getSupersedesPlanId());
        return newPlan;
    }

    /** 方案列表（不含 content 正文，按时间倒序；当前方案 = 最新一条 active） */
    public List<CareerPlan> listPlans(Long userId) {
        QueryWrapper<CareerPlan> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .select("id", "title", "status", "created_at")
                .orderByDesc("created_at")
                .orderByDesc("id");
        List<CareerPlan> list = careerPlanMapper.selectList(wrapper);
        log.info("查询用户 userId={} 的行动方案列表，共 {} 条", userId, list.size());
        return list;
    }

    /** 方案详情（含 content 正文）；仅返回归属当前用户的那一份，否则返回 null */
    public CareerPlan getDetail(Long userId, Long planId) {
        QueryWrapper<CareerPlan> wrapper = new QueryWrapper<>();
        wrapper.eq("id", planId).eq("user_id", userId);
        return careerPlanMapper.selectOne(wrapper);
    }

    /**
     * 修改方案正文（markdown 全文）；仅归属当前用户可改，未命中返回 null。
     * title 同步取正文首个 markdown 标题（# 级标题的第一行），保证编辑后列表标题与正文一致；
     * 若正文没有标题则保留原标题。
     */
    public CareerPlan updateContent(Long userId, Long planId, String content) {
        CareerPlan plan = getDetail(userId, planId);
        if (plan == null) {
            return null;
        }

        CareerPlan patch = new CareerPlan();
        patch.setId(planId);
        patch.setContent(content);
        String newTitle = extractTitleFromContent(content);
        if (newTitle != null) {
            patch.setTitle(newTitle);
            plan.setTitle(newTitle);
        }
        careerPlanMapper.updateById(patch);

        plan.setContent(content);
        log.info("修改行动方案成功 userId={}, planId={}", userId, planId);
        return plan;
    }

    /**
     * 删除方案（物理删除）；仅归属当前用户可删，未命中返回 false。
     * 若删除的是当前 active，则把该用户剩余最新一条 archived 提升为 active，
     * 维持「同 user 最新一条 active = 当前方案」不变式；历史链 supersedes_plan_id 不做清理（仅溯源用）。
     */
    @Transactional
    public boolean deletePlan(Long userId, Long planId) {
        CareerPlan plan = getDetail(userId, planId);
        if (plan == null) {
            return false;
        }
        boolean wasActive = "active".equals(plan.getStatus());

        careerPlanMapper.deleteById(planId);

        if (wasActive) {
            QueryWrapper<CareerPlan> wrapper = new QueryWrapper<>();
            wrapper.eq("user_id", userId)
                    .eq("status", "archived")
                    .orderByDesc("created_at")
                    .orderByDesc("id")
                    .last("LIMIT 1");
            CareerPlan latestArchived = careerPlanMapper.selectOne(wrapper);
            if (latestArchived != null) {
                CareerPlan patch = new CareerPlan();
                patch.setId(latestArchived.getId());
                patch.setStatus("active");
                careerPlanMapper.updateById(patch);
                log.info("删除当前方案后，方案 planId={} 提升为 active", latestArchived.getId());
            }
        }

        log.info("删除行动方案成功 userId={}, planId={}", userId, planId);
        return true;
    }

    /** 取 markdown 正文首个标题行（如 "## 目标" → "目标"）；无标题返回 null */
    private String extractTitleFromContent(String content) {
        if (content == null) {
            return null;
        }
        for (String rawLine : content.split("\n")) {
            String line = rawLine == null ? "" : rawLine.trim();
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
