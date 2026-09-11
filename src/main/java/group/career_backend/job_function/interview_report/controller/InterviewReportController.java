package group.career_backend.job_function.interview_report.controller;

import group.career_backend.common.Result;
import group.career_backend.job_function.interview_report.domain.dto.InterviewReportCreateDTO;
import group.career_backend.job_function.interview_report.domain.po.InterviewReport;
import group.career_backend.job_function.interview_report.service.InterviewReportService;
import group.career_backend.security.UserContext;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/users/me/interview-reports")
@RequiredArgsConstructor
@Slf4j
public class InterviewReportController {
    private final InterviewReportService interviewReportService;

    @PostMapping
    public Result<?> create(@RequestBody InterviewReportCreateDTO dto, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] POST /users/me/interview-reports, userId={}, authenticated={}",
                userId, UserContext.isAuthenticated(request));
        String title = dto.getTitle();
        String content = dto.getContent();
        if (title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            log.info("[接口完成] 面试报告参数校验失败, userId={}", userId);
            return Result.error(400, "title 和 content 不能为空");
        }

        InterviewReport report = interviewReportService.createReport(
                userId, title.trim(), content, dto.getSessionId(),
                dto.getJobId(), dto.getJobName(), dto.getCompanyName());
        Map<String, Object> data = new HashMap<>();
        data.put("reportId", report.getId());
        data.put("title", report.getTitle());
        log.info("[接口完成] 面试报告保存成功, userId={}, reportId={}", userId, report.getId());
        return Result.success(data, "面试报告已保存");
    }

    @GetMapping
    public Result<?> list(HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/interview-reports, userId={}, authenticated={}",
                userId, UserContext.isAuthenticated(request));
        List<InterviewReport> reports = interviewReportService.listReports(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (InterviewReport report : reports) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", report.getId());
            item.put("title", report.getTitle());
            item.put("jobId", report.getJobId());
            item.put("jobName", report.getJobName());
            item.put("companyName", report.getCompanyName());
            item.put("createdAt", report.getCreatedAt() == null ? null : report.getCreatedAt().toString());
            items.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", items.size());
        data.put("list", items);
        log.info("[接口完成] 面试报告列表查询成功, userId={}, count={}", userId, items.size());
        return Result.success(data);
    }

    @GetMapping("/{reportId}")
    public Result<?> detail(@PathVariable Long reportId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] GET /users/me/interview-reports/{reportId}, userId={}, reportId={}, authenticated={}",
                userId, reportId, UserContext.isAuthenticated(request));
        InterviewReport report = interviewReportService.getDetail(userId, reportId);
        if (report == null) {
            log.info("[接口完成] 面试报告不存在或无权访问, userId={}, reportId={}", userId, reportId);
            return Result.error(404, "报告不存在或无权访问");
        }
        log.info("[接口完成] 面试报告详情查询成功, userId={}, reportId={}", userId, reportId);
        return Result.success(toDetailMap(report));
    }

    @DeleteMapping("/{reportId}")
    public Result<?> delete(@PathVariable Long reportId, HttpServletRequest request) {
        Long userId = UserContext.getUserId(request);
        log.info("[接口访问] DELETE /users/me/interview-reports/{reportId}, userId={}, reportId={}, authenticated={}",
                userId, reportId, UserContext.isAuthenticated(request));
        if (!interviewReportService.deleteReport(userId, reportId)) {
            log.info("[接口完成] 面试报告不存在或无权删除, userId={}, reportId={}", userId, reportId);
            return Result.error(404, "报告不存在或无权访问");
        }
        log.info("[接口完成] 面试报告删除成功, userId={}, reportId={}", userId, reportId);
        return Result.success(null, "报告已删除");
    }

    private Map<String, Object> toDetailMap(InterviewReport report) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", report.getId());
        data.put("title", report.getTitle());
        data.put("content", report.getContent());
        data.put("jobId", report.getJobId());
        data.put("jobName", report.getJobName());
        data.put("companyName", report.getCompanyName());
        data.put("sessionId", report.getSessionId());
        data.put("createdAt", report.getCreatedAt() == null ? null : report.getCreatedAt().toString());
        return data;
    }
}
