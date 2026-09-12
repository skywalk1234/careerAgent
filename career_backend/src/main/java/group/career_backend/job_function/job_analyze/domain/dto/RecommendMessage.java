package group.career_backend.job_function.job_analyze.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RecommendMessage implements Serializable {
    private Long userId;
    private MatchFilter filters;
}
