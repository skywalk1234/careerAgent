package group.career_backend.job_function.job_analyze.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiAnalyzeRequest {
    private Object profile;
    private Object job;
}
