package group.career_backend.job_function.job_analyze.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class JobBrief {
    private String recordId;
    private String source;
    private Boolean pinned;
    private String jobId;
    private String jobName;
    private String companyName;
    private String city;
    private Boolean salaryNegotiable;
    private String salaryNormalized;
    private String updatedAtRaw;
    private Integer overallScore;
    private List<String> matchTags;
    private String createdAt;
    private String updatedAt;
}
