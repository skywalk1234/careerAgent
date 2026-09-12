package group.careerservice.controller;/* I love coding */

import group.careerservice.domain.dto.InterviewReportCreateDTO;
import group.careerservice.domain.po.InterviewReport;
import group.careerservice.service.InterviewReport.InterviewReportService;
import group.common.Result;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * AI 模拟面试专家 · 面试总结报告的存储与查询接口（前端经网关 /api 访问，Python 专家经网关 POST 落库）。
 * 网关 career-user 路由（Path=/users/me/** → career-service）已覆盖本前缀，无需新增路由。
 */
@RestController
@Slf4j
@RequiredArgsConstructor
@RequestMapping("/users/me/interview-reports")
public class InterviewReportController {

    private final InterviewReportService interviewReportService;

    /** 创建报告：Python 报告工具（submit_mock_interview_report）生成后调用 */
    @PostMapping
    public Result create(@RequestBody InterviewReportCreateDTO dto) {
        String title = dto.getTitle();
        String content = dto.getContent();
        if (title == null || title.trim().isEmpty() || content == null || content.trim().isEmpty()) {
            return Result.error(400, "title 和 content 不能为空");
        }
        Long userId = getUserId();
        InterviewReport report = interviewReportService.createReport(
                userId, title.trim(), content, dto.getSessionId(),
                dto.getJobId(), dto.getJobName(), dto.getCompanyName());
        Map<String, Object> data = new HashMap<>();
        data.put("reportId", report.getId());
        data.put("title", report.getTitle());
        return Result.success(data, "面试报告已保存");
    }

    /** 报告列表（不含 content，按时间倒序） */
    @GetMapping
    public Result list() {
        Long userId = getUserId();
        List<InterviewReport> reports = interviewReportService.listReports(userId);
        List<Map<String, Object>> items = new ArrayList<>();
        for (InterviewReport report : reports) {
            Map<String, Object> item = new HashMap<>();
            item.put("id", report.getId());
            item.put("title", report.getTitle());
            item.put("jobId", report.getJobId());
            item.put("jobName", report.getJobName());
            item.put("companyName", report.getCompanyName());
            item.put("createdAt", report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
            items.add(item);
        }
        Map<String, Object> data = new HashMap<>();
        data.put("total", items.size());
        data.put("list", items);
        return Result.success(data);
    }

    /** 报告详情（含 content 全文）；校验归属，非本人 404 */
    @GetMapping("/{reportId}")
    public Result detail(@PathVariable Long reportId) {
        Long userId = getUserId();
        InterviewReport report = interviewReportService.getDetail(userId, reportId);
        if (report == null) {
            return Result.error(404, "报告不存在或无权访问");
        }
        return Result.success(reportDetailMap(report));
    }

    /** 删除报告；校验归属，非本人 404 */
    @DeleteMapping("/{reportId}")
    public Result delete(@PathVariable Long reportId) {
        Long userId = getUserId();
        boolean deleted = interviewReportService.deleteReport(userId, reportId);
        if (!deleted) {
            return Result.error(404, "报告不存在或无权访问");
        }
        return Result.success(null, "报告已删除");
    }

    /** 报告详情字段（与 detail 返回结构一致） */
    private Map<String, Object> reportDetailMap(InterviewReport report) {
        Map<String, Object> data = new HashMap<>();
        data.put("id", report.getId());
        data.put("title", report.getTitle());
        data.put("content", report.getContent());
        data.put("jobId", report.getJobId());
        data.put("jobName", report.getJobName());
        data.put("companyName", report.getCompanyName());
        data.put("sessionId", report.getSessionId());
        data.put("createdAt", report.getCreatedAt() != null ? report.getCreatedAt().toString() : null);
        return data;
    }

    /** 当前登录用户 id；网关已做 JWT 校验，理论上必不为空，兜底对齐 career-service 其它接口的 "111" 写法 */
    private Long getUserId() {
        Long userId = UserContext.getUser();
        return userId != null ? userId : 111L;
    }
}
