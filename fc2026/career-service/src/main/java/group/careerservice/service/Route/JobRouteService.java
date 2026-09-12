package group.careerservice.service.Route;/* I love coding */

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import group.careerservice.TestConstant.DeepEvalTestConstant;
import group.careerservice.client.AiClient;
import group.careerservice.client.ProfileClient;
import group.careerservice.domain.dto.*;
import group.careerservice.domain.po.*;
import group.careerservice.domain.response.DeepEvalStatusRes;
import group.careerservice.domain.response.RealTimeEvalRes;
import group.careerservice.domain.vo.DeepEvalVO;
import group.careerservice.domain.vo.SimpleRouteVO;
import group.careerservice.mapper.*;
import group.careerservice.service.JobExploration.SaveJobService;
import group.common.Result;
import group.tool.IdGenerator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class JobRouteService {
    private final RabbitTemplate rabbitTemplate;
    private final SaveJobService saveJobService;
    private final AiClient aiClient;
    private final DeepEvalMapper deepEvalMapper;
    private final AnalysisDraftMapper analysisDraftMapper;
    private final ProfileClient profileClient;
    private final JobNodesMapper jobNodesMapper;
    private final JobPromotionMapper jobPromotionMapper;
    private final JobTransferMapper jobTransferMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
//    监听route_planning队列的消息
    @RabbitListener(queues = "route_planning")
    public void autoPlanRoute(Map<String, Object> msg) throws JsonProcessingException {
        String userId = (String) msg.get("userId");
        String autoPlanJobId = (String) msg.get("autoPlanJobId");
        String jobId = (String) msg.get("jobId");
        String mode = (String) msg.get("mode");
        Result profile = profileClient.getProfile(userId);
        String profileJson = objectMapper.writeValueAsString(profile.getData());

//        根据jobid查询到岗位名字
        JobDocument jobDocument = saveJobService.queryJobById(jobId);
        String jobName = jobDocument.getJobName();
        log.info("岗位中心节点：{}", jobName);
        List<String> categoryNames = new ArrayList<>();
//        找出nodeId
        QueryWrapper<JobNodesPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("node_name", jobName);
        JobNodesPO jobNodesPO = jobNodesMapper.selectOne(queryWrapper);
        String jobNodeId = jobNodesPO.getNodeId();
//        找出前驱 前驱和后继我都假设有很多个
        QueryWrapper<JobPromotionPO> queryWrapper_pre = new QueryWrapper<>();
        queryWrapper_pre.eq("target", jobNodeId);
        List<JobPromotionPO> jobPromotionPOs = jobPromotionMapper.selectList(queryWrapper_pre);

        jobPromotionPOs.forEach(jobPromotionPO -> {
            categoryNames.add(jobPromotionPO.getMetadata().getSource());
        });

//        找出后继
        QueryWrapper<JobPromotionPO> queryWrapper_next = new QueryWrapper<>();
        queryWrapper_next.eq("source", jobNodeId);
        List<JobPromotionPO> jobPromotionPOs_next = jobPromotionMapper.selectList(queryWrapper_next);
        jobPromotionPOs_next.forEach(jobPromotionPO -> {
            categoryNames.add(jobPromotionPO.getMetadata().getTarget());
        });
//        找出一步的换岗
        QueryWrapper<JobTransferPO> queryWrapper_transfer = new QueryWrapper<>();
        queryWrapper_transfer.eq("source", jobNodeId);
        List<JobTransferPO> jobTransferPOs = jobTransferMapper.selectList(queryWrapper_transfer);
        jobTransferPOs.forEach(jobTransferPO -> {
            categoryNames.add(jobTransferPO.getMetadata().getTarget());
        });
//        这里的categoryName还是节点id,要转换成节点名字

        for (int i=0; i<categoryNames.size(); i++) {
            QueryWrapper<JobNodesPO> queryWrapper_category = new QueryWrapper<>();
            queryWrapper_category.eq("node_id", categoryNames.get(i));
            JobNodesPO jobNodesPO_ = jobNodesMapper.selectOne(queryWrapper_category);
            String categoryName = jobNodesPO_.getNodeName();
            categoryNames.set(i, categoryName);
        }
        categoryNames.add(jobName);
        log.info("categoryNames: {}", categoryNames);
        Map<String, Object> request = new HashMap<>();
        request.put("categoryList", categoryNames);
        request.put("profile",profileJson);
        log.info("请求ai进行规划路径");

        String s = aiClient.routePlanning(request);
        log.info("ai返回规划结果：{}", s);
        DeepEvalStatusRes deepEvalStatusRes = objectMapper.readValue(s, DeepEvalStatusRes.class);

        DeepEvalInfoDTO deepEvalInfoDTO = new DeepEvalInfoDTO();
        deepEvalInfoDTO.setPathEdges(deepEvalStatusRes.getPathEdges());
        deepEvalInfoDTO.setEvaluation(deepEvalStatusRes.getEvaluation());

        DeepEvalPO deepEvalPO = DeepEvalPO.builder()
                .userId(Long.parseLong(userId))
                .pathId(IdGenerator.generateShortId())
                .saveJobId(autoPlanJobId)
                .pathName(deepEvalStatusRes.getPathName())
                .deepEvalInfo(deepEvalInfoDTO)
                .pathNodes(deepEvalStatusRes.getPathNodes())
                .pathNodeCount(deepEvalStatusRes.getPathNodes().size())
                .feasibilityScore(deepEvalStatusRes.getEvaluation().getFeasibilityScore()).build();
        int insert = deepEvalMapper.insert(deepEvalPO);
        if (insert > 0) {
            log.info("保存自动推荐结果成功");
        }
        else {
            log.info("保存自动推荐结果失败");
        }

    }
//    路径轻评估
    public RealTimeEvalRes realtimeEvaluate(String userId, SimpleRouteVO vo) throws JsonProcessingException {
        List<JobNodeDTO> pathNodes = new ArrayList<>();
        List<SimpleRouteVO.PathNode> pathNodes_with_name = new ArrayList<>();
        for (int i=0; i<vo.getPathNodes().size(); i++) {
            JobDocument jobDocument = saveJobService.queryJobById(vo.getPathNodes().get(i).getJobId());
            pathNodes.add(new JobNodeDTO(vo.getPathNodes().get(i).getId(), jobDocument, vo.getPathNodes().get(i).getStage()));
            SimpleRouteVO.PathNode pathNode = vo.getPathNodes().get(i);
            pathNode.setJobName(jobDocument.getJobName());
            pathNodes_with_name.add(pathNode);
        }
        String pathNodeJson = convertPathNodesToJson(pathNodes);
        log.info("开始给ai服务请求轻评估");
        long startTime = System.currentTimeMillis();
        String result = aiClient.realTimeEval(pathNodeJson);
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
        log.info("ai轻评估完成，耗时: {} ms", duration);
        ObjectMapper mapper = new ObjectMapper();

        String cleanedJson = result.replaceAll("^```(json)?\\s*", "")
                .replaceAll("\\s*```$", "")
                .trim();
//        System.out.println("评估结果\n"+ cleanedJson);

        // 解析JSON到对象
        CareerPathEvaluation evaluation = mapper.readValue(cleanedJson, CareerPathEvaluation.class);
        //保存到数据库中
//       拿到draft_id
//        String draftId = "draft_id_1";
        RealTimeEvalRes realTimeEvalRes = new RealTimeEvalRes(userId, "auto", LocalDateTime.now().toString(), pathNodes_with_name, evaluation.getPathEdges(), evaluation.getEvaluation());
        DraftInfoDTO draftInfoDTO = new DraftInfoDTO();
        BeanUtil.copyProperties(realTimeEvalRes, draftInfoDTO);
        draftInfoDTO.setDraftId(IdGenerator.generateShortId());
        draftInfoDTO.setCreatedAt(LocalDateTime.now().toString());
        draftInfoDTO.setUpdatedAt(LocalDateTime.now().toString());

        AnalysisDraftPO analysisDraftPO = new AnalysisDraftPO();
        analysisDraftPO.setUserId(Long.parseLong(userId));
        analysisDraftPO.setDraftId(draftInfoDTO.getDraftId());
        analysisDraftPO.setDraftInfo(draftInfoDTO);
//        替换掉相同userId的草稿信息
        LambdaQueryWrapper<AnalysisDraftPO> deleteWrapper = new LambdaQueryWrapper<>();
        deleteWrapper.eq(AnalysisDraftPO::getUserId, analysisDraftPO.getUserId());
        analysisDraftMapper.delete(deleteWrapper);
        int insert = analysisDraftMapper.insert(analysisDraftPO);
        if (insert > 0) {
            log.info("保存轻评估草稿成功");
        } else {
            log.info("保存轻评估草稿失败");
        }


        return realTimeEvalRes;


    }


    // 路径深评估
    @RabbitListener(queues = "deep_eval")
    public void deepEvaluate(Map<String, Object> message) throws JsonProcessingException {
        String userId = (String) message.get("userId");
        String save_job_id = (String) message.get("saveJobId");
        DeepEvalVO deepEvalVO = (DeepEvalVO) message.get("requestBody");
        String pathId = IdGenerator.generateShortId();
//        将节点列表中的jobid真正查询出Job详细信息并替换
        List<JobNodeDTO> pathNodesDetail = new ArrayList<>();
//        需要将查询出来的岗位名称回填到下面这个列表中
        List<SimpleRouteVO.PathNode> pathNodesSimple = new ArrayList<>();

        for (int i=0; i<deepEvalVO.getPathNodes().size(); i++) {
            JobDocument jobDocument = saveJobService.queryJobById(deepEvalVO.getPathNodes().get(i).getJobId());
            if(jobDocument != null) {
                pathNodesDetail.add(new JobNodeDTO(deepEvalVO.getPathNodes().get(i).getId(), jobDocument, deepEvalVO.getPathNodes().get(i).getStage()));
                SimpleRouteVO.PathNode pathNode = deepEvalVO.getPathNodes().get(i);
                pathNode.setJobName(jobDocument.getJobName());
                pathNodesSimple.add(pathNode);
            }
        }
        String pathNodeJson = convertPathNodesToJson(pathNodesDetail);
//        String response = "";


        log.info("准备调用大模型进行深度评估");
//        调用大模型分析
        long startTime = System.currentTimeMillis();
        String response = aiClient.deepEval(pathNodeJson);

// 2. 计算耗时
        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;
// 3. 打印结果
        log.info("ai轻评估完成，耗时: {} ms", duration);


//        String response = DeepEvalTestConstant.test_json;


        ObjectMapper mapper = new ObjectMapper();
        DeepEvalInfoDTO deepEvalInfoDTO = mapper.readValue(response, DeepEvalInfoDTO.class);

        DeepEvalPO deepEvalPO = DeepEvalPO.builder()
                .userId(Long.parseLong(userId))
                .pathId(pathId)
                .saveJobId(save_job_id)
                .pathName(deepEvalVO.getPathName())
                .deepEvalInfo(deepEvalInfoDTO)
                .pathNodeCount(deepEvalVO.getPathNodes().size())
                .feasibilityScore(deepEvalInfoDTO.getEvaluation().getFeasibilityScore())
                .pathNodes(pathNodesSimple)
                .build();

        int insert = deepEvalMapper.insert(deepEvalPO);
        if (insert > 0) {
            log.info("保存深度评估结果成功");
        }else {
            log.info("保存深度评估结果失败");
        }

    }


    public String convertPathNodesToJson(List<JobNodeDTO> pathNodes) {
        try {
            ObjectMapper objectMapper = new ObjectMapper();

            // 配置美化输出（可选）
            objectMapper.enable(SerializationFeature.INDENT_OUTPUT);

            // 处理日期时间（如果JobNodeDTO中有LocalDateTime等）
            objectMapper.registerModule(new JavaTimeModule());
            objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

            // 转换对象为JSON字符串
            return objectMapper.writeValueAsString(pathNodes);
        } catch (Exception e) {
            throw new RuntimeException("JSON转换失败", e);
        }
    }

    public DraftInfoDTO getDraftInfo(String draftId) {
        LambdaQueryWrapper<AnalysisDraftPO> queryWrapper = new LambdaQueryWrapper<>();
        queryWrapper.eq(AnalysisDraftPO::getDraftId, draftId);
        AnalysisDraftPO analysisDraftPO = analysisDraftMapper.selectOne(queryWrapper);
        if (analysisDraftPO == null) {
            return null;
        }
        DraftInfoDTO draftInfoDTO = analysisDraftPO.getDraftInfo();
        return draftInfoDTO;
    }

}
