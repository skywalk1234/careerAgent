package group.careerservice.service.InterviewReport;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.careerservice.domain.po.InterviewReport;
import group.careerservice.mapper.InterviewReportMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 模拟面试总结报告的存储与查询（表 user_interview_reports）。
 * 报告每场面试一份、不版本化（无 status/supersedes），不做内容修改——只创建 / 列表 / 详情 / 删除。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InterviewReportService {

    private final InterviewReportMapper interviewReportMapper;

    /** 创建报告（Python 报告工具调用）：jobId/jobName/companyName 快照可空 */
    public InterviewReport createReport(Long userId, String title, String content, String sessionId,
                                        String jobId, String jobName, String companyName) {
        InterviewReport report = new InterviewReport();
        report.setUserId(userId);
        report.setTitle(title);
        report.setContent(content);
        report.setSessionId(sessionId);
        report.setJobId(jobId);
        report.setJobName(jobName);
        report.setCompanyName(companyName);
        report.setCreatedAt(LocalDateTime.now());
        interviewReportMapper.insert(report);
        log.info("创建面试总结报告成功 userId={}, reportId={}", userId, report.getId());
        return report;
    }

    /** 报告列表（不含 content 正文，按时间倒序） */
    public List<InterviewReport> listReports(Long userId) {
        QueryWrapper<InterviewReport> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId)
                .select("id", "title", "job_id", "job_name", "company_name", "created_at")
                .orderByDesc("created_at")
                .orderByDesc("id");
        List<InterviewReport> list = interviewReportMapper.selectList(wrapper);
        log.info("查询用户 userId={} 的面试报告列表，共 {} 条", userId, list.size());
        return list;
    }

    /** 报告详情（含 content 全文）；仅返回归属当前用户的那一份，否则返回 null */
    public InterviewReport getDetail(Long userId, Long reportId) {
        QueryWrapper<InterviewReport> wrapper = new QueryWrapper<>();
        wrapper.eq("id", reportId).eq("user_id", userId);
        return interviewReportMapper.selectOne(wrapper);
    }

    /** 删除报告（物理删除）；仅归属当前用户可删，未命中返回 false */
    public boolean deleteReport(Long userId, Long reportId) {
        InterviewReport report = getDetail(userId, reportId);
        if (report == null) {
            return false;
        }
        interviewReportMapper.deleteById(reportId);
        log.info("删除面试总结报告成功 userId={}, reportId={}", userId, reportId);
        return true;
    }
}
