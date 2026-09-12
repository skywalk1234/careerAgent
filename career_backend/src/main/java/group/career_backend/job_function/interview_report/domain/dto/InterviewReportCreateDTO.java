package group.career_backend.job_function.interview_report.domain.dto;

import lombok.Data;

@Data
public class InterviewReportCreateDTO {
    private String title;
    private String content;
    private String sessionId;
    private String jobId;
    private String jobName;
    private String companyName;
}
