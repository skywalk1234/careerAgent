package group.careerservice.controller;

import group.careerservice.domain.dto.JobCleanupRequestDTO;
import group.careerservice.domain.dto.JobCleanupResponseDTO;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.dto.JobEdgeGenerateRequestDTO;
import group.careerservice.domain.dto.JobEdgeGenerateResponseDTO;
import group.careerservice.domain.dto.JobEdgeGenerateStatusDTO;
import group.careerservice.domain.dto.JobImportRequestDTO;
import group.careerservice.domain.dto.JobImportResponseDTO;
import group.careerservice.domain.dto.JobImportStatusDTO;
import group.careerservice.domain.vo.JobsFilter;
import group.careerservice.service.JobCleanup.JobCleanupService;
import group.careerservice.service.JobEdgeGeneration.JobEdgeGenerationService;
import group.careerservice.service.JobExploration.SaveJobService;
import group.careerservice.service.JobImport.JobImportService;
import group.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/admin")
@RequiredArgsConstructor
@Slf4j
public class AdminController {

    private final JobImportService jobImportService;
    private final JobEdgeGenerationService jobEdgeGenerationService;
    private final SaveJobService saveJobService;
    private final JobCleanupService jobCleanupService;

    @PostMapping("/job-graph/import-jobs")
    public Result<JobImportResponseDTO> createImportJob(@RequestBody JobImportRequestDTO request) {
        log.info("接收到创建岗位导入任务请求，batchName: {}, sourceType: {}",
            request.getBatchName(), request.getSourceType());

        String importJobId = jobImportService.createImportJob(request);

        JobImportResponseDTO response = new JobImportResponseDTO();
        response.setImportJobId(importJobId);
        response.setStatus("processing");
        response.setPollAfterMs(1200L);

        log.info("岗位导入任务创建成功，importJobId: {}", importJobId);
        return Result.success(response, "岗位导入任务创建成功");
    }

    @GetMapping("/job-graph/import-jobs/{importJobId}")
    public Result<JobImportStatusDTO> getImportJobStatus(@PathVariable("importJobId") String importJobId) {
        log.info("接收到查询岗位导入任务状态请求，importJobId: {}", importJobId);

        JobImportStatusDTO status = jobImportService.getImportStatus(importJobId);

        if (status == null) {
            log.warn("未找到导入任务，importJobId: {}", importJobId);
            return Result.error(404, "导入任务不存在");
        }

        String msg;
        switch (status.getStatus()) {
            case "processing":
                msg = "岗位导入进行中";
                break;
            case "succeeded":
                msg = "岗位导入完成";
                break;
            case "failed":
                msg = "岗位导入失败";
                break;
            default:
                msg = "未知状态";
        }

        log.info("查询导入任务状态成功，importJobId: {}, status: {}", importJobId, status.getStatus());
        return Result.success(status, msg);
    }

    @PostMapping("/job-graph/generate-edges")
    public Result<JobEdgeGenerateResponseDTO> createEdgeGenerationJob(@RequestBody JobEdgeGenerateRequestDTO request) {
        log.info("接收到创建岗位关系边生成任务请求，batchId: {}", request.getBatchId());

        String edgeJobId = jobEdgeGenerationService.createEdgeGenerationJob(request);

        JobEdgeGenerateResponseDTO response = new JobEdgeGenerateResponseDTO();
        response.setEdgeJobId(edgeJobId);
        response.setStatus("processing");
        response.setPollAfterMs(1200L);

        log.info("岗位关系边生成任务创建成功，edgeJobId: {}", edgeJobId);
        return Result.success(response, "关系边生成任务创建成功");
    }

    @GetMapping("/job-graph/generate-edges/{edgeJobId}")
    public Result<JobEdgeGenerateStatusDTO> getEdgeGenerationStatus(@PathVariable("edgeJobId") String edgeJobId) {
        log.info("接收到查询岗位关系边生成任务状态请求，edgeJobId: {}", edgeJobId);

        JobEdgeGenerateStatusDTO status = jobEdgeGenerationService.getEdgeGenerationStatus(edgeJobId);

        if (status == null) {
            log.warn("未找到边生成任务，edgeJobId: {}", edgeJobId);
            return Result.error(404, "边生成任务不存在");
        }

        String msg;
        switch (status.getStatus()) {
            case "processing":
                msg = "关系边生成进行中";
                break;
            case "succeeded":
                msg = "关系边生成完成";
                break;
            case "failed":
                msg = "关系边生成失败";
                break;
            default:
                msg = "未知状态";
        }

        log.info("查询边生成任务状态成功，edgeJobId: {}, status: {}", edgeJobId, status.getStatus());
        return Result.success(status, msg);
    }

    @GetMapping("/job-graph/jobs")
    public Result queryAdminJobs(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String city,
            @RequestParam(defaultValue = "1") Integer page,
            @RequestParam(defaultValue = "20") Integer pageSize,
            @RequestParam(defaultValue = "updatedAt") String sortBy,
            @RequestParam(defaultValue = "desc") String sortOrder) {
        log.info("接收到管理端岗位分页查询请求，keyword: {}, status: {}, city: {}, page: {}, pageSize: {}",
            keyword, status, city, page, pageSize);

        JobsFilter filters = JobsFilter.builder()
            .keyword(keyword)
            .city(city)
            .page(page)
            .pageSize(pageSize)
            .sortBy(sortBy)
            .sortOrder(sortOrder)
            .build();

        Page<JobDocument> jobs = saveJobService.queryJobsByFilter(filters);
        log.info("管理端岗位查询成功，总条数: {}", jobs.getTotalElements());

        Map<String, Object> response = new HashMap<>();
        response.put("total", jobs.getTotalElements());
        response.put("page", page);
        response.put("pageSize", pageSize);
        response.put("list", jobs.getContent());

        return Result.success(response);
    }

    @PostMapping("/job-graph/jobs/cleanup-expired")
    public Result<JobCleanupResponseDTO> cleanupExpiredJobs(@RequestBody JobCleanupRequestDTO request) {
        log.info("接收到清理过期岗位请求，expireDays: {}, dryRun: {}",
            request.getExpireDays(), request.getDryRun());

        JobCleanupResponseDTO response = jobCleanupService.cleanupExpiredJobs(request);

        String msg = Boolean.TRUE.equals(request.getDryRun()) ? "试运行模式：发现过期岗位" : "过期岗位清理完成";
        log.info("{}, expireDays: {}, removedCount: {}", msg, response.getExpireDays(), response.getRemovedCount());

        return Result.success(response, "过期岗位清理完成");
    }
}
