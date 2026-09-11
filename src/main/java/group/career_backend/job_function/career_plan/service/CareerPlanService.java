package group.career_backend.job_function.career_plan.service;

import group.career_backend.job_function.career_plan.domain.po.CareerPlan;

import java.util.List;

public interface CareerPlanService {

    CareerPlan createPlan(Long userId, String title, String content, String sessionId);

    List<CareerPlan> listPlans(Long userId);

    CareerPlan getDetail(Long userId, Long planId);

    CareerPlan updateContent(Long userId, Long planId, String content);

    boolean deletePlan(Long userId, Long planId);
}
