package group.career_backend.job_function.career_plan.domain.dto;

import lombok.Data;

@Data
public class CareerPlanCreateDTO {
    private String title;
    private String content;
    private String sessionId;
}
