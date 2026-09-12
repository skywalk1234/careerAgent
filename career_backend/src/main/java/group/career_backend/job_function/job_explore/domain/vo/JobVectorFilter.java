package group.career_backend.job_function.job_explore.domain.vo;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobVectorFilter {
    private String keyword;
    private String city;
    private Double salaryMin;
    private Double salaryMax;
    private List<String> salaryTier;
    private List<String> exp;
    private List<String> edu;

    @Builder.Default
    private String sortBy = "salary";

    @Builder.Default
    private String sortOrder = "desc";

    @Builder.Default
    private Integer page = 1;

    @Builder.Default
    private Integer pageSize = 20;
}
