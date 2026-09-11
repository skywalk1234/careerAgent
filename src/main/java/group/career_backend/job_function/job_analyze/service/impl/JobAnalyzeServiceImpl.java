package group.career_backend.job_function.job_analyze.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.career_backend.exception.CommonException;
import group.career_backend.job_function.job_analyze.domain.dto.AiAnalyzeRequest;
import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobRequest;
import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobResult;
import group.career_backend.job_function.job_analyze.domain.dto.JobBrief;
import group.career_backend.job_function.job_analyze.domain.po.AnalyzeJob;
import group.career_backend.job_function.job_analyze.mapper.AnalyzeJobMapper;
import group.career_backend.job_function.job_analyze.service.JobAnalyzeService;
import group.career_backend.job_function.job_analyze.service.JobDetailProvider;
import group.career_backend.profile.domain.response.GetProfileResponse;
import group.career_backend.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class JobAnalyzeServiceImpl implements JobAnalyzeService {
    private final AnalyzeJobMapper analyzeJobMapper;
    private final ProfileService profileService;
    private final JobDetailProvider jobDetailProvider;
    private final JobAnalyzeAiClient aiClient;

    @Override
    @Transactional
    public AnalyzeOutcome analyze(Long userId, AnalyzeJobRequest request) {
        log.info("[业务处理] 开始岗位匹配深度分析, userId={}, jobId={}", userId, request.getJobId());
        GetProfileResponse profile = profileService.getProfileResponse(userId);
        if (profile == null || !profile.isHasProfile() || profile.getProfile() == null) {
            throw new CommonException("请先完成画像分析后再进行岗位分析", 409);
        }
        Object job = jobDetailProvider.getJobDetail(request.getJobId());
        AnalyzeJobResult result = aiClient.analyze(new AiAnalyzeRequest(profile.getProfile(), job));

        AnalyzeJob previous = null;
        if (Boolean.TRUE.equals(request.getOverwriteSameJob())) {
            previous = analyzeJobMapper.selectOne(new LambdaQueryWrapper<AnalyzeJob>()
                    .eq(AnalyzeJob::getUserId, userId)
                    .eq(AnalyzeJob::getJobId, request.getJobId())
                    .orderByDesc(AnalyzeJob::getUpdatedAt)
                    .orderByDesc(AnalyzeJob::getId)
                    .last("LIMIT 1"));
        }

        AnalyzeJob record = new AnalyzeJob();
        record.setUserId(userId);
        record.setJobId(request.getJobId());
        record.setAnalysis(result);
        record.setUpdatedAt(LocalDateTime.now());
        if (analyzeJobMapper.insert(record) != 1) {
            throw new CommonException("保存岗位分析结果失败", 500);
        }
        Long removedId = null;
        if (previous != null && analyzeJobMapper.delete(new LambdaQueryWrapper<AnalyzeJob>()
                .eq(AnalyzeJob::getId, previous.getId())
                .eq(AnalyzeJob::getUserId, userId)) == 1) {
            removedId = previous.getId();
        }
        log.info("[业务处理] 岗位匹配深度分析完成, userId={}, jobId={}, recordId={}",
                userId, request.getJobId(), record.getId());
        return new AnalyzeOutcome(record, removedId != null, removedId);
    }

    @Override
    public AnalyzeJob getDetail(Long userId, Long recordId) {
        log.info("[业务处理] 查询岗位分析历史详情, userId={}, recordId={}", userId, recordId);
        return analyzeJobMapper.selectOne(new LambdaQueryWrapper<AnalyzeJob>()
                .eq(AnalyzeJob::getId, recordId)
                .eq(AnalyzeJob::getUserId, userId)
                .last("LIMIT 1"));
    }

    @Override
    public List<JobBrief> getHistory(Long userId) {
        log.info("[业务处理] 查询岗位分析历史列表, userId={}", userId);
        List<AnalyzeJob> records = analyzeJobMapper.selectList(new LambdaQueryWrapper<AnalyzeJob>()
                .eq(AnalyzeJob::getUserId, userId)
                .orderByDesc(AnalyzeJob::getUpdatedAt)
                .orderByDesc(AnalyzeJob::getId));
        List<JobBrief> result = new ArrayList<>();
        for (AnalyzeJob record : records) {
            if (record.getAnalysis() != null) {
                result.add(toBrief(record));
            }
        }
        log.info("[业务处理] 岗位分析历史列表查询完成, userId={}, count={}", userId, result.size());
        return result;
    }

    private JobBrief toBrief(AnalyzeJob record) {
        AnalyzeJobResult result = record.getAnalysis();
        AnalyzeJobResult.JobInfo job = result.getJob();
        AnalyzeJobResult.AnalyzeInfo analysis = result.getAnalysis();
        JobBrief brief = new JobBrief();
        brief.setRecordId(record.getId().toString());
        brief.setSource("favorite_panel");
        brief.setPinned(false);
        brief.setJobId(record.getJobId());
        if (job != null) {
            brief.setJobName(job.getJobName());
            brief.setCompanyName(job.getCompanyName());
            brief.setCity(job.getCity());
            brief.setSalaryNegotiable(job.getSalaryNegotiable());
            brief.setSalaryNormalized(job.getSalaryNormalized());
            brief.setUpdatedAtRaw(job.getUpdatedAtRaw());
        }
        if (analysis != null) {
            brief.setOverallScore(analysis.getOverallScore());
            brief.setMatchTags(analysis.getMatchTags());
        }
        String databaseTime = record.getUpdatedAt() == null ? null : record.getUpdatedAt().toString();
        brief.setCreatedAt(databaseTime);
        brief.setUpdatedAt(databaseTime);
        return brief;
    }
}
