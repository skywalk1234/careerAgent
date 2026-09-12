package group.careerservice.service.JobAnalyze;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.TestConstant.AI_recommendation;
import group.careerservice.client.AiClient;
import group.careerservice.client.ProfileClient;
import group.careerservice.domain.dto.AnalyzeJobDTO;
import group.careerservice.domain.dto.JobBrief;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.po.AnalyzeJobPo;
import group.vo.AnalyzeVO;
import group.careerservice.mapper.AnalyzeMapper;
import group.careerservice.service.JobExploration.SaveJobService;
import group.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class JobAnalyzeService {

    private final SaveJobService saveJobService;
    private final ProfileClient profileClient;
    private final AiClient aiClient;
    private final AnalyzeMapper analyzeMapper;
//    用于接收岗位匹配分析，深度分析的请求
    public AnalyzeJobPo analyze(String userId, AnalyzeVO vo) throws Exception{

        log.info("开始精细化分析岗位");
        log.info("用户ID：{}", userId);
        JobDocument jobDocument = saveJobService.queryJobById(vo.getJobId());
        Result profile = profileClient.getProfile(userId);
        Object data = profile.getData();//把这个东西转成json字符串，把jobs也转成字符串，全部扔给ai
        ObjectMapper objectMapper = new ObjectMapper();
        String profile_json = objectMapper.writeValueAsString(data);

        String jobs_json = objectMapper.writeValueAsString(jobDocument);
        String info_json = String.format(
                "学生画像信息:\n%s\n\n岗位信息:\n%s",
                profile_json,
                jobs_json
        );

// 调用 AI
        String response = aiClient.analyzeJobs(info_json);

//        字段解析成下面这个类
        AnalyzeJobDTO analyzeJobDTO = objectMapper.readValue(response, AnalyzeJobDTO.class);


        AnalyzeJobPo analyzeJobPo = new AnalyzeJobPo();
        analyzeJobPo.setUserId(Long.parseLong(userId));
        analyzeJobPo.setJobId(vo.getJobId());
        analyzeJobPo.setAnalysis(analyzeJobDTO);
        analyzeJobPo.setUpdatedAt(LocalDateTime.now());

        int insert = analyzeMapper.insert(analyzeJobPo);
        if (insert == 1) {
            log.info("插入成功");
            return analyzeJobPo;
        } else {
            log.info("插入失败");
            return null;
        }


    }

//    用于接收查询分析历史的详细信息
    public AnalyzeJobPo getDetail(String recordId){
        log.info("开始查询分析历史详细信息");
        log.info("分析记录ID：{}", recordId);
        AnalyzeJobPo analyzeJobPo = analyzeMapper.selectById(recordId);
        return analyzeJobPo;
    }
//    用于接收查询分析历史列表
    public List<JobBrief> getHistoryList(String userId) {
        // 参数校验
        if (userId == null || userId.trim().isEmpty()) {
            return new ArrayList<>();
        }

        try {
            // 将userId转换为Long
            Long userIdLong = Long.parseLong(userId);

            // 构建查询条件
            QueryWrapper<AnalyzeJobPo> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("user_id", userIdLong);

            // 查询数据库
            List<AnalyzeJobPo> analyzeJobPos = analyzeMapper.selectList(queryWrapper);

            if (analyzeJobPos == null || analyzeJobPos.isEmpty()) {
                return new ArrayList<>();
            }

            // 转换为JobBrief列表
            List<JobBrief> jobBriefs = analyzeJobPos.stream()
                    .map(this::convertToJobBrief)
                    .collect(Collectors.toList());

            return jobBriefs;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("userId格式不正确，应为有效的数字字符串", e);
        }
    }
    private JobBrief convertToJobBrief(AnalyzeJobPo analyzeJobPo) {
        if (analyzeJobPo == null || analyzeJobPo.getAnalysis() == null) {
            return null;
        }

        AnalyzeJobDTO analysis = analyzeJobPo.getAnalysis();
        JobBrief jobBrief = new JobBrief();

        // 设置基本信息
        jobBrief.setRecordId(analyzeJobPo.getId().toString());  // 假设recordId格式为"mr_" + id
        jobBrief.setSource("favorite_panel");  // 默认值，根据实际情况调整
        jobBrief.setPinned(false);   // 默认值，根据实际情况调整
        jobBrief.setJobId(analyzeJobPo.getJobId());

        // 设置分析结果中的信息
        if (analysis.getJob() != null) {
            AnalyzeJobDTO.JobInfo job = analysis.getJob();
            jobBrief.setJobName(job.getJobName());
            jobBrief.setCompanyName(job.getCompanyName());
            jobBrief.setCity(job.getCity());
            jobBrief.setSalaryNegotiable(job.getSalaryNegotiable());
            jobBrief.setSalaryNormalized(job.getSalaryNormalized());
            jobBrief.setUpdatedAtRaw(job.getUpdatedAtRaw());
        }

        jobBrief.setOverallScore(analysis.getAnalysis().getOverallScore());
        jobBrief.setMatchTags(analysis.getAnalysis().getMatchTags());


        // 设置时间（从analysis中获取，或使用数据库时间）
        jobBrief.setCreatedAt(LocalDateTime.now().toString());
        jobBrief.setUpdatedAt(LocalDateTime.now().toString());

        return jobBrief;
    }
}
