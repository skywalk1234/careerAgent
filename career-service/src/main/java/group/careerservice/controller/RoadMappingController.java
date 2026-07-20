package group.careerservice.controller;/* I love coding */

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.domain.dto.ExportReportRequestDTO;
import group.careerservice.domain.dto.ExportReportResponseDTO;
import group.careerservice.domain.dto.ReportDTO;
import group.careerservice.domain.dto.ReportListDTO;
import group.careerservice.domain.vo.ReportVO;
import group.careerservice.service.RoadMapping.ExportService;
import group.careerservice.service.RoadMapping.PolishService;
import group.careerservice.service.RoadMapping.RoadMappingService;
import group.tool.IdGenerator;
import group.common.Result;
import group.utils.UserContext;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.bind.annotation.*;

import javax.servlet.http.HttpServletResponse;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping
@RequiredArgsConstructor
@Slf4j
public class RoadMappingController {
    private final RoadMappingService roadMappingService;
    private final RabbitTemplate rabbitTemplate;
    private final PolishService polishService;
    private final ExportService exportService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // 生成职业生涯报告任务 pass
    @PostMapping("/users/me/career-report/generate")
    public Result generateCareerReport(@RequestBody Map<String, Object> requestBody) {
        log.info("接收到生成职业生涯报告请求");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        String reportJobId = IdGenerator.generateShortId();
        requestBody.put("userId", userId);
        requestBody.put("reportJobId", reportJobId);
        // 从请求体中获取参数
        String pathId = (String) requestBody.get("pathId");
        String reportTitle = (String) requestBody.get("reportTitle");
        String templateVersion = (String) requestBody.get("templateVersion");


        log.info("路径 ID: {}, 报告标题：{}, 模板版本：{}", pathId, reportTitle, templateVersion);

        rabbitTemplate.convertAndSend("job_road_mapping", requestBody);

        // 构建响应数据
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("reportJobId", reportJobId);
        responseData.put("status", "processing");
        responseData.put("pollAfterMs", 2000);

        log.info("报告生成任务已创建，任务 ID: {}", reportJobId);
        return Result.success(responseData, "报告生成任务已创建");
    }
//  查询报告状态， pass
    @GetMapping("/users/me/career-report/generate-jobs/{reportJobId}")
    public Result queryReportStatus(@PathVariable String reportJobId) throws JsonProcessingException {

        log.info("接收到查询报告状态请求，报告任务 ID: {}", reportJobId);
        ReportDTO reportDTO = roadMappingService.queryReportStatus(reportJobId, 0);
        if(reportDTO == null){
            Map<String, Object> map = new HashMap<>();
            map.put("reportJobId", reportJobId);
            map.put("status", "processing");
            map.put("progress",99);
            map.put("pollAfterMs", 2000);
        }
        // 创建外层 Map
        Map<String, Object> jobMap = new HashMap<>();

        // 设置 reportJobId
        jobMap.put("reportJobId", reportJobId);

        // 设置 status
        jobMap.put("status", "succeeded");

        // 创建 result Map
        Map<String, Object> resultMap = new HashMap<>();
        // 创建 report Map（内部对象）
        Map<String, Object> reportMap = new HashMap<>();
        // 这里你可以根据实际内容填充 reportMap，目前用空 Map 占位
        // 例如：reportMap.put("key", "value");

        resultMap.put("report", reportDTO);
        jobMap.put("result", resultMap);
        return Result.success(jobMap, "报告状态查询成功");
    }
//  查询报告列表 pass
    @GetMapping("/users/me/career-report/list")
    public Result queryReportList() {
        log.info("接收到查询报告列表请求");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        List<ReportListDTO> reportList = roadMappingService.getReportList(userId);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("total", reportList.size());
        responseData.put("latestReportId", reportList.get(0).getReportId());
        responseData.put("list", reportList);
        return Result.success(responseData, "OK");
    }
// 查询最新报告 pass
    @GetMapping("/users/me/career-report/latest")
    public Result queryLatestReport() {
        log.info("接收到查询最新报告请求");
        Long userIdLong = UserContext.getUser();
        String userId = userIdLong != null ? userIdLong.toString() : "111";
        ReportDTO latestReport = roadMappingService.getLatestReport(userId);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("hasReport", latestReport != null);
        responseData.put("latestReportId", latestReport.getReportId());
        responseData.put("report", latestReport);

        return Result.success(responseData, "OK");


    }
//  获取单份报告内容 pass
    @GetMapping("/users/me/career-report/{reportId}")
    public Result queryReport(@PathVariable String reportId) throws JsonProcessingException {
        log.info("接收到查询报告请求，报告 ID: {}", reportId);
        ReportDTO reportDTO = roadMappingService.queryReportStatus(reportId, 1);
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("hasReport", reportDTO != null);
        responseData.put("latestReportId", reportDTO.getReportId());
        responseData.put("report", reportDTO);
        return Result.success(responseData, "OK");
    }

//    创建润色任务 pass
    @PostMapping("/users/me/career-report/{reportId}/polish")
    public Result createPolishTask(@PathVariable String reportId, @RequestBody Map<String, Object> requestBody) throws JsonProcessingException {
        log.info("接收到创建润色任务请求，报告 ID: {}", reportId);
        String polishJobId = IdGenerator.generateShortId();

        Object focusSections = requestBody.get("focusSections");
        String polishAdvice = objectMapper.writeValueAsString(focusSections);
        log.info("润色建议：{}", polishAdvice);
        Map<String, String> msg = new HashMap<>();
        msg.put("reportId", reportId);
        msg.put("polishJobId", polishJobId);
        msg.put("polishAdvice", polishAdvice);

        rabbitTemplate.convertAndSend("polish_with_advice", msg);

        // 创建一个模拟的运行结果
        Map<String, Object> result = new HashMap<>();
        result.put("polishJobId", polishJobId);
        result.put("reportId", reportId);
        result.put("status", "processing");
        result.put("pollAfterMs", 1200);

        return Result.success(result, "润色任务已创建");
    }
//  查询润色任务状态 pass
    @GetMapping("/users/me/career-report/polish-jobs/{polishJobId}")
    public Result queryPolishStatus(@PathVariable String polishJobId) {
        log.info("接收到查询润色任务状态请求，任务 ID: {}", polishJobId);
        ReportDTO reportDTO = polishService.queryPolishStatus(polishJobId);

        Map<String, Object> responseData = new HashMap<>();
        responseData.put("polishJobId", polishJobId);


        if(reportDTO != null){
            responseData.put("reportId", reportDTO.getReportId());
            responseData.put("status", "succeeded");
            responseData.put("result", reportDTO);
            return Result.success(responseData, "润色");
        }else{

            responseData.put("status", "processing");
            responseData.put("progress", 99);
            responseData.put("pollAfterMs", 2000);
            return Result.success(responseData, "处理中");
        }
    }

//    ai自动润色
    @PostMapping("/users/me/career-report/{reportId}/polish-by-advice-jobs")
    public Result PolishAuto(@PathVariable String reportId) {
        log.info("接收到自动润色请求，报告 ID: {}", reportId);
        String polishJobId = IdGenerator.generateShortId();

        Map<String, Object> msg = new HashMap<>();
        msg.put("reportId", reportId);
        msg.put("polishJobId", polishJobId);
//    由JobRouteService中的deepEvaluate进行监听
        rabbitTemplate.convertAndSend("polish_auto", msg);
        Map<String, Object> dataMap = new HashMap<>();

        // 添加数据
        dataMap.put("polishJobId", polishJobId);
        dataMap.put("reportId", reportId);
        dataMap.put("status", "processing");
        dataMap.put("pollAfterMs", 1200);
        return Result.success(dataMap, "一键润色任务已创建");
    }

//    全量更新报告 pass
    @PutMapping("/users/me/career-report/{reportId}")
    public Result updateReport(@PathVariable String reportId, @RequestBody ReportVO vo) {
        log.info("接收到更新报告请求，报告 ID: {}", reportId);
        vo.setReportId(reportId);
        Integer i = polishService.updateReport(vo);
        if (i == 1) {
            log.info("更新报告成功");
            Map<String, Object> map = new HashMap<>();
            map.put("reportId", reportId);
            map.put("version", 2);
            map.put("updatedAt", LocalDateTime.now().toString());
            return Result.success(map, "更新成功");
        }
        return Result.error(200, "更新失败");
    }

//    导出报告
    @PostMapping("/users/me/career-report/{reportId}/export")
    public Result exportReport(@PathVariable String reportId, @RequestBody ExportReportRequestDTO requestDTO) {
        log.info("接收到导出报告请求，报告 ID: {}, 格式: {}", reportId, requestDTO.getFormat());

        // 验证请求参数
        String format = requestDTO.getFormat();
        if (format == null || format.trim().isEmpty()) {
            return Result.error(400, "导出格式不能为空");
        }

        format = format.toLowerCase().trim();
        ExportReportResponseDTO responseDTO;

        // 根据格式调用对应的导出服务
        switch (format) {
            case "pdf":
                responseDTO = exportService.exportReportToPdf(reportId, requestDTO);
                break;
            case "docx":
                responseDTO = exportService.exportReportToDocx(reportId, requestDTO);
                break;
            case "markdown":
            case "md":
                responseDTO = exportService.exportReportToMarkdown(reportId, requestDTO);
                break;
            default:
                return Result.error(400, "不支持的导出格式: " + format + "，目前支持 pdf、docx、markdown");
        }

        // 构建响应
        Map<String, Object> responseData = new HashMap<>();
        responseData.put("exportJobId", responseDTO.getExportJobId());
        responseData.put("status", responseDTO.getStatus());
        responseData.put("pollAfterMs", responseDTO.getPollAfterMs());

        if ("success".equals(responseDTO.getStatus())) {
            responseData.put("downloadUrl", responseDTO.getDownloadUrl());
            responseData.put("fileName", responseDTO.getFileName());
            return Result.success(responseData, "导出成功");
        } else {
            responseData.put("errorMessage", responseDTO.getErrorMessage());
            return Result.error(500, responseDTO.getErrorMessage());
        }
    }

//    下载导出的报告文件
    @GetMapping("/career-reports/downloads/{fileName}")
    public void downloadReport(@PathVariable String fileName, HttpServletResponse response) throws IOException {
        log.info("接收到下载报告请求，文件名: {}", fileName);

        // 获取文件路径
        String filePath = exportService.getExportFilePath(fileName);
        File file = new File(filePath);

        if (!file.exists()) {
            log.error("文件不存在: {}", filePath);
            response.setStatus(HttpServletResponse.SC_NOT_FOUND);
            response.getWriter().write("文件不存在");
            return;
        }

        // 根据文件扩展名设置ContentType
        String contentType = getContentTypeByFileName(fileName);
        response.setContentType(contentType);

        String encodedFileName = URLEncoder.encode(fileName, StandardCharsets.UTF_8).replaceAll("\\+", "%20");
        response.setHeader("Content-Disposition", "attachment; filename=\"" + encodedFileName + "\"");
        response.setContentLength((int) file.length());

        // 写入文件内容
        try (FileInputStream fis = new FileInputStream(file);
             OutputStream os = response.getOutputStream()) {
            byte[] buffer = new byte[4096];
            int bytesRead;
            while ((bytesRead = fis.read(buffer)) != -1) {
                os.write(buffer, 0, bytesRead);
            }
            os.flush();
        }

        log.info("文件下载完成: {}", fileName);
    }

    /**
     * 根据文件名获取ContentType
     */
    private String getContentTypeByFileName(String fileName) {
        if (fileName == null) {
            return "application/octet-stream";
        }
        String lowerFileName = fileName.toLowerCase();
        if (lowerFileName.endsWith(".pdf")) {
            return "application/pdf";
        } else if (lowerFileName.endsWith(".docx")) {
            return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        } else if (lowerFileName.endsWith(".md") || lowerFileName.endsWith(".markdown")) {
            return "text/markdown";
        }
        return "application/octet-stream";
    }

}
