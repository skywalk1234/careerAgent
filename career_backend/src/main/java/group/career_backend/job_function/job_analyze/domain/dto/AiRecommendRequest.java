package group.career_backend.job_function.job_analyze.domain.dto;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class AiRecommendRequest {
    private Long userId;
    private Object profile;

    @JsonProperty("求职意愿")
    private MatchFilter filters;
}
