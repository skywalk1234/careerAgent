package group.careerservice.service.RoadMapping;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.client.AiClient;
import group.careerservice.domain.dto.ReportDTO;
import group.careerservice.domain.dto.RoadMappingDTO.ReportSections;
import group.careerservice.domain.po.CareerRoadMappingPO;
import group.careerservice.domain.vo.ReportVO;
import group.careerservice.mapper.RoadMappingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class PolishService {
    private final RoadMappingMapper roadMappingMapper;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final AiClient aiClient;

//    监听创建ai根据建议润色的任务
    @RabbitListener(queues = "polish_with_advice")
    public void startPolish(Map<String, String>  message) throws JsonProcessingException {
        String reportId = message.get("reportId");
        String polishJobId = message.get("polishJobId");
        String polishAdvice = message.get("polishAdvice");

        QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("report_id", reportId);
        CareerRoadMappingPO po = roadMappingMapper.selectOne(queryWrapper);
        po.setPolishJobId(polishJobId);
        ReportSections reportSections = po.getReportSections();
//        获取需要被ai润色的报告
        String reportSectionsJson = objectMapper.writeValueAsString(reportSections);
        String res_json = aiClient.polishRoadmapping(reportSectionsJson+polishAdvice);
//        String res_json = reportSectionsJson;
        ReportSections res = objectMapper.readValue(res_json, ReportSections.class);
        po.setReportSections(res);
        po.setUpdatedAt(LocalDateTime.now());
        int i = roadMappingMapper.updateById(po);
        if (i == 1) {
            log.info("更新成功");
        }
        else {
            log.info("更新失败");
        }

    }

    @RabbitListener(queues = "polish_auto")
    public void PolishAuto(Map<String, String>  message) throws JsonProcessingException {
        String reportId = message.get("reportId");
        String polishJobId = message.get("polishJobId");
        QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("report_id", reportId);
        CareerRoadMappingPO po = roadMappingMapper.selectOne(queryWrapper);
//        如果用户同时对同一个报告既点了一键润色和建议润色，那会出错
        po.setPolishJobId(polishJobId);
        ReportSections reportSections = po.getReportSections();
        String reportSectionsJson = objectMapper.writeValueAsString(reportSections);


//        String res_json = aiClient.hahahah();
        String res_json = reportSectionsJson;
        ReportSections res = objectMapper.readValue(res_json, ReportSections.class);
        po.setReportSections(res);
        int i = roadMappingMapper.updateById(po);
        if (i == 1) {
            log.info("更新润色之后的报告成功");
        }
        else {
            log.info("更新润色之后的报告失败");
        }
    }

    public ReportDTO queryPolishStatus(String polishJobId){
        QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("polish_job_id", polishJobId);
        CareerRoadMappingPO po = roadMappingMapper.selectOne(queryWrapper);
        if(po == null){
            return null;
        }
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
                .generatedAt(po.getGeneratedAt())
                .updatedAt(po.getUpdatedAt()).build();

    }

    public Integer updateReport(ReportVO vo){
        QueryWrapper<CareerRoadMappingPO> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("report_id", vo.getReportId());
        CareerRoadMappingPO po = roadMappingMapper.selectOne(queryWrapper);
        po.setReportTitle(vo.getReportTitle());
        po.setStatus(vo.getStatus());
        po.setReportSections(vo.getReportSections());
        po.setUpdatedAt(LocalDateTime.now());
        return roadMappingMapper.updateById(po);

    }
}
