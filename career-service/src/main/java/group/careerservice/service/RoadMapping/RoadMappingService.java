package group.careerservice.service.RoadMapping;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.TestConstant.RoadMappingConstant;
import group.careerservice.client.AiClient;
import group.careerservice.client.ProfileClient;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.dto.ReportDTO;
import group.careerservice.domain.dto.ReportListDTO;
import group.careerservice.domain.dto.RoadMappingDTO.*;
import group.careerservice.domain.po.CareerRoadMappingPO;
import group.careerservice.domain.response.DeepEvalStatusRes;
import group.careerservice.mapper.RoadMappingMapper;
import group.careerservice.service.JobExploration.SaveJobService;
import group.careerservice.service.Route.GetDeepEvalService;
import group.tool.IdGenerator;
import group.common.Result;
import group.dto.GetProfileResponse;
import group.dto.ResumeEvaluationResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoadMappingService {
    private final GetDeepEvalService getDeepEvalService;
    private final SaveJobService saveJobService;
    private final ProfileClient profileClient;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiClient aiClient;
    private final RoadMappingMapper roadMappingMapper;

    @RabbitListener(queues = "job_road_mapping")
    public void startGenerateRoadMapping(Map<String, Object> requestBody) {
        String pathId = (String) requestBody.get("pathId");
        String userId = (String) requestBody.get("userId");
        String reportTitle = (String) requestBody.get("reportTitle");
        String templateVersion = (String) requestBody.get("templateVersion");
        String reportJobId = (String) requestBody.get("reportJobId");
//        根据pathId查询路径
        DeepEvalStatusRes evalRes = getDeepEvalService.getEvalByPathId(pathId);

//        获取路径节点对应的岗位详细信息
        List<JobDocument> jobs = evalRes.getPathNodes().stream()
                .map(pathNode -> saveJobService.queryJobById(pathNode.getJobId()))
                .toList();
//        获取学生画像
        Result profile = profileClient.getProfile(userId);
        profile.getData();

        try {
            String evalResJson = objectMapper.writeValueAsString(evalRes);
            String jobsJson = objectMapper.writeValueAsString(jobs);
            String profileJson = objectMapper.writeValueAsString(profile.getData());
            Map<String, String> requestMap = Map.of(
                    "reportTitle", reportTitle,
                    "templateVersion", templateVersion,
                    "evalRes", evalResJson,
                    "jobs", jobsJson,
                    "profile", profileJson
            );
            String request_json = objectMapper.writeValueAsString(requestMap);
            log.info("请求体：{}", request_json);
            log.info("开始发送给ai生成路径报告");

            String response = aiClient.createRoadmapping(request_json);
//            String response = RoadMappingConstant.test;

//            开始组装整个路径报告
            PathRef pathRef = getPathRef(evalRes);
            log.info("组装pathRef完成");
            ProfileSnapshot profileSnapshot = getProfileSnapshot(profile);
            log.info("组装profileSnapshot完成");
            EvaluationSnapshot evaluationSnapshot = getEvaluationSnapshot(evalRes);
            String jsonString = objectMapper.writeValueAsString(profile.getData());

            // 再解析为目标对象
            GetProfileResponse profileData = objectMapper.readValue(jsonString, GetProfileResponse.class);

            evaluationSnapshot.setAbilityComparison(getAbilityComparison(evalRes, profileData, jobs.get(jobs.size()-1)));
            log.info("组装evaluationSnapshot完成");
            ReportSections reportSections = getReportSections(response);
            log.info("组装reportSections完成");
            EditingMeta editingMeta = new EditingMeta(1, LocalDateTime.now().toString(), userId);
            String reportId = IdGenerator.generateShortId();

            CareerRoadMappingPO po = CareerRoadMappingPO.builder()
                    .userId(userId)
                    .reportJobId(reportJobId)
                    .reportId(reportId)
                    .reportTitle(reportTitle)
                    .templateVersion(templateVersion)
                    .status("draft")
                    .pathRef(pathRef)
                    .profileSnapshot(profileSnapshot)
                    .evaluationSnapShot(evaluationSnapshot)
                    .reportSections(reportSections)
                    .editingMeta(editingMeta)
                    .generatedAt(LocalDateTime.now()).build();
            int insert = roadMappingMapper.insert(po);
            if (insert > 0){
                log.info("路径报告保存到数据库成功");
            }else {
                log.error("路径报告保存到数据库失败");
            }


        } catch (Exception e) {
            log.error("JSON 转换失败", e);

        }
    }

    public ReportDTO queryReportStatus(String reportJobId, Integer choice) throws JsonProcessingException {
//        choice如果是0的话根据reportJobId查询, 1的话根据reportId查询
        CareerRoadMappingPO po = null;
        if (choice == 0){
            QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("report_job_id", reportJobId);
            po = roadMappingMapper.selectOne(queryWrapper);
        }
        if(choice == 1){
            QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("report_id", reportJobId);
            po = roadMappingMapper.selectOne(queryWrapper);
        }
        if (po == null) return null;
        return ReportDTO.builder()
                .reportId(po.getReportId())
                .reportTitle(po.getReportTitle())
                .templateVersion(po.getTemplateVersion())
                .status(po.getStatus())
                .pathRef(po.getPathRef())
                .profileSnapshot(po.getProfileSnapshot())
                .evaluationSnapshot(po.getEvaluationSnapShot())
                .reportSections(po.getReportSections())
                .editingMeta(po.getEditingMeta())
                .updatedAt(po.getUpdatedAt())
                .generatedAt(po.getGeneratedAt()).build();

    }

    public List<ReportListDTO> getReportList(String userId){
        QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId)
                .orderByDesc("generated_at");
        List<CareerRoadMappingPO> list = roadMappingMapper.selectList(queryWrapper);

        return list.stream().map(po -> ReportListDTO.builder()
                .reportId(po.getReportId())
                .reportTitle(po.getReportTitle())
                .status(po.getStatus())
                .pathRef(po.getPathRef())
                .version(po.getEditingMeta().getVersion())
                .generatedAt(po.getGeneratedAt())
                .updatedAt(po.getUpdatedAt())
                .build()).toList();
    }

    public ReportDTO getLatestReport(String userId) {
        QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId)
                .orderByDesc("generated_at")
                .last("limit 1"); // 限制只取一条
        CareerRoadMappingPO po = roadMappingMapper.selectOne(queryWrapper);
        return ReportDTO.builder()
                .reportId(po.getReportId())
                .reportTitle(po.getReportTitle())
                .templateVersion(po.getTemplateVersion())
                .status(po.getStatus())
                .pathRef(po.getPathRef())
                .profileSnapshot(po.getProfileSnapshot())
                .evaluationSnapshot(po.getEvaluationSnapShot())
                .reportSections(po.getReportSections())
                .editingMeta(po.getEditingMeta())
                .updatedAt(po.getUpdatedAt())
                .generatedAt(po.getGeneratedAt()).build();
    }







    public PathRef getPathRef(DeepEvalStatusRes evalRes) {
        PathRef pathRef = new PathRef();
        pathRef.setPathId(evalRes.getPathId());
        pathRef.setPathName(evalRes.getPathName());
        pathRef.setPathNodeCount(evalRes.getPathNodes().size());
        pathRef.setTargetJobId(evalRes.getPathNodes().get(evalRes.getPathNodes().size()-1).getJobId());
        pathRef.setTargetJobName(evalRes.getPathNodes().get(evalRes.getPathNodes().size()-1).getJobName());

        return pathRef;
    }
    public ProfileSnapshot getProfileSnapshot(Result profile) throws JsonProcessingException {
        ProfileSnapshot profileSnapshot = new ProfileSnapshot();
        String jsonString = objectMapper.writeValueAsString(profile.getData());

        // 再解析为目标对象
        GetProfileResponse profileData = objectMapper.readValue(jsonString, GetProfileResponse.class);


        profileSnapshot.setProfileId(profileData.getProfileId());
        profileSnapshot.setName(profileData.getProfile().getBasicInfo().getName());
        profileSnapshot.setMajor(profileData.getProfile().getEducation().get(profileData.getProfile().getEducation().size()-1).getMajor());
        profileSnapshot.setCity(profileData.getProfile().getBasicInfo().getCity());
        profileSnapshot.setJobIntention(profileData.getProfile().getBasicInfo().getJobIntention());
        profileSnapshot.setCompletenessScore(profileData.getScores().getCompletenessScore());
        profileSnapshot.setCompetitivenessScore(profileData.getScores().getCompetitivenessScore());
        return profileSnapshot;

    }

    public EvaluationSnapshot getEvaluationSnapshot(DeepEvalStatusRes evalRes) {
        EvaluationSnapshot evaluationSnapshot = new EvaluationSnapshot();
        evaluationSnapshot.setReadinessScore(evalRes.getEvaluation().getReadinessScore());
        evaluationSnapshot.setFeasibilityScore(evalRes.getEvaluation().getFeasibilityScore());
        evaluationSnapshot.setRecommendationScore(evalRes.getEvaluation().getRecommendationScore());
        evaluationSnapshot.setRiskAlerts(evalRes.getEvaluation().getRiskAlerts());
        evaluationSnapshot.setSummaryMetrics(evalRes.getEvaluation().getSummaryMetrics());

        return evaluationSnapshot;

    }

    public EvaluationSnapshot.AbilityComparison getAbilityComparison(DeepEvalStatusRes evalRes, GetProfileResponse profile, JobDocument targetJob) throws IllegalAccessException {
        EvaluationSnapshot.AbilityComparison abilityComparison = new EvaluationSnapshot.AbilityComparison();
        List<EvaluationSnapshot.AbilityComparison.Dimension> dimensions = new ArrayList<>();

        ResumeEvaluationResult.AbilityScores abilityScores = profile.getScores().getAbilityScores();
        //用反射得到属性名和属性值
//        这里能不能做到还得看测试结果
        Field[] fields = ResumeEvaluationResult.AbilityScores.class.getDeclaredFields();
        Map<String, String> keyLabelMap = getKeyLabelMap();
        Field[] jobFields = JobDocument.AbilityRequirements.class.getDeclaredFields();
        for (int i = 0; i < 12; i++) {
            EvaluationSnapshot.AbilityComparison.Dimension dimension = new EvaluationSnapshot.AbilityComparison.Dimension();


            dimension.setKey(fields[i].getName());
            dimension.setLabel(keyLabelMap.get(fields[i].getName()));
            dimension.setStudentScore((Integer) fields[i].get(abilityScores));
            dimension.setTargetRequiredScore((Integer) jobFields[i].get(targetJob.getAbilityRequirements()));
//            abilityComparison.getDimensions().add(dimension);
            dimensions.add( dimension);
        }
        abilityComparison.setDimensions(dimensions);
        return abilityComparison;
    }

    public Map<String,String> getKeyLabelMap(){
        // 根据提供的JSON数据创建Map<String, String>
        Map<String, String> keyLabelMap = new HashMap<>();
        keyLabelMap.put("professionalSkill", "专业技能");
        keyLabelMap.put("certificate", "证书能力");
        keyLabelMap.put("innovation", "创新能力");
        keyLabelMap.put("internalMotivation", "内驱动力");
        keyLabelMap.put("learning", "学习能力");
        keyLabelMap.put("stressTolerance", "抗压能力");
        keyLabelMap.put("communication", "沟通能力");
        keyLabelMap.put("internship", "实习能力");
        keyLabelMap.put("language", "语言能力");
        keyLabelMap.put("leadership", "领导能力");
        keyLabelMap.put("adaptability", "适应能力");
        keyLabelMap.put("execution", "执行能力");
        return keyLabelMap;
    }

    public ReportSections getReportSections(String res_json) throws JsonProcessingException {
        ReportSections reportSections = new ReportSections();
        reportSections = objectMapper.readValue(res_json, ReportSections.class);
        return reportSections;
    }

}
