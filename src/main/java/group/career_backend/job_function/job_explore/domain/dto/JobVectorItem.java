package group.career_backend.job_function.job_explore.domain.dto;

import lombok.Data;

import java.util.List;

@Data
public class JobVectorItem {
    private String jobId;
    private String bossJobId;
    private String jobName;
    private String companyName;
    private String city;
    private List<String> cats;
    private String salaryText;
    private Double salaryMin;
    private Double salaryMax;
    private String salaryUnit;
    private Double avg;
    private String tier;
    private String exp;
    private String edu;
    private String source;
    private String sourceSite;
    private String sourceUrl;
    private String updatedAtRaw;
    private String lastSeen;
    private String firstSeen;
    private Boolean isNew;
    private Boolean vectorReady;
    private String jobDescription;
}
