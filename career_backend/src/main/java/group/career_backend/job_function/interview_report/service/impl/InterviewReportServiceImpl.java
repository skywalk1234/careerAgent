package group.career_backend.job_function.interview_report.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.career_backend.job_function.interview_report.domain.po.InterviewReport;
import group.career_backend.job_function.interview_report.mapper.InterviewReportMapper;
import group.career_backend.job_function.interview_report.service.InterviewReportService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class InterviewReportServiceImpl implements InterviewReportService {
    private final InterviewReportMapper interviewReportMapper;

    @Override
    public InterviewReport createReport(Long userId, String title, String content, String sessionId,
                                        String jobId, String jobName, String companyName) {
        log.info("[业务处理] 开始创建面试报告, userId={}", userId);
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
        log.info("[业务处理] 面试报告创建完成, userId={}, reportId={}", userId, report.getId());
        return report;
    }

    @Override
    public List<InterviewReport> listReports(Long userId) {
        log.info("[业务处理] 开始查询面试报告列表, userId={}", userId);
        LambdaQueryWrapper<InterviewReport> wrapper = new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getUserId, userId)
                .select(InterviewReport::getId, InterviewReport::getTitle, InterviewReport::getJobId,
                        InterviewReport::getJobName, InterviewReport::getCompanyName, InterviewReport::getCreatedAt)
                .orderByDesc(InterviewReport::getCreatedAt)
                .orderByDesc(InterviewReport::getId);
        List<InterviewReport> reports = interviewReportMapper.selectList(wrapper);
        log.info("[业务处理] 面试报告列表查询完成, userId={}, count={}", userId, reports.size());
        return reports;
    }

    @Override
    public InterviewReport getDetail(Long userId, Long reportId) {
        log.info("[业务处理] 开始查询面试报告详情, userId={}, reportId={}", userId, reportId);
        LambdaQueryWrapper<InterviewReport> wrapper = new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getId, reportId)
                .eq(InterviewReport::getUserId, userId);
        InterviewReport report = interviewReportMapper.selectOne(wrapper);
        log.info("[业务处理] 面试报告详情查询完成, userId={}, reportId={}, found={}",
                userId, reportId, report != null);
        return report;
    }

    @Override
    public boolean deleteReport(Long userId, Long reportId) {
        log.info("[业务处理] 开始删除面试报告, userId={}, reportId={}", userId, reportId);
        LambdaQueryWrapper<InterviewReport> wrapper = new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getId, reportId)
                .eq(InterviewReport::getUserId, userId);
        int deleted = interviewReportMapper.delete(wrapper);
        log.info("[业务处理] 面试报告删除完成, userId={}, reportId={}, deleted={}",
                userId, reportId, deleted);
        return deleted > 0;
    }
}
