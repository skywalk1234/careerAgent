package group.career_backend.job_function.interview_report.service;

import group.career_backend.job_function.interview_report.domain.po.InterviewReport;

import java.util.List;

public interface InterviewReportService {
    InterviewReport createReport(Long userId, String title, String content, String sessionId,
                                 String jobId, String jobName, String companyName);

    List<InterviewReport> listReports(Long userId);

    InterviewReport getDetail(Long userId, Long reportId);

    boolean deleteReport(Long userId, Long reportId);
}
