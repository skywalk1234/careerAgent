package group.resumeparserservice.controller;/* I love coding */

import group.common.Result;
import group.resumeparserservice.domain.dto.ResumeParseMessage;
import group.resumeparserservice.domain.response.FileParseRes;

import group.resumeparserservice.service.AI_image_parser;
import group.resumeparserservice.service.AI_road_mapping;
import group.resumeparserservice.service.AI_route_eval;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
@RestController
public class FileController {
    private final AI_route_eval ai_route_eval;
    @Autowired
    private RabbitTemplate rabbitTemplate;

    private final AI_road_mapping ai_road_mapping;
    private final AI_image_parser ai_image_parser;
//  上传pdf并解析
    @RequestMapping("/users/me/profile/parse-jobs")
    public Result<FileParseRes> uploadPdf(@RequestParam("file") MultipartFile file,
                                          @RequestParam("parseMode") String parseMode) {
        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }
        // 1. 获取文件名
        String fileName = file.getOriginalFilename();
        // 3. 获取文件大小
        long size = file.getSize();

        // 4. 获取文件类型
        String contentType = file.getContentType();
        // 5. 业务逻辑处理...
        System.out.println("文件名: " + fileName);
        System.out.println("类型: " + contentType);
        //解析简历
        //jobid由解析token的user_id得到，全局唯一

        try {
            ResumeParseMessage message = new ResumeParseMessage();
            //从ThreadLocal获取userId
            Long userId = UserContext.getUser();
            message.setUserId(userId != null ? userId : 23L);
            message.setFileName(fileName);
            message.setFileContent(file.getBytes());
            message.setFileType(contentType);
            message.setParseMode(parseMode);
            String queue = "file_tran";
            rabbitTemplate.convertAndSend(queue, message);
        } catch (IOException e) {
            throw new RuntimeException(e);
        }


        FileParseRes res = new FileParseRes();
        res.setStatus("processing");
        res.setParseJobId("1234567890");
        res.setPollAfterMs(2000);//两秒轮询一次
        return Result.success(res);
    }

    /**
     * 上传图片简历并解析
     * 支持格式：jpg, jpeg, png, webp
     */
    @PostMapping("/users/me/profile/parse-image")
    public Result<FileParseRes> uploadImageResume(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return Result.error(400, "文件不能为空");
        }

        String fileName = file.getOriginalFilename();
        String contentType = file.getContentType();

        // 检查是否为图片类型
        if (!isImageFile(contentType)) {
            return Result.error(400, "不支持的文件类型，请上传图片文件(jpg, jpeg, png, gif, bmp, webp)");
        }

        log.info("接收到图片简历，文件名: {}, 类型: {}", fileName, contentType);

        try {
            // 调用千问VL模型解析图片
            String parseResult = ai_image_parser.parseResumeImage(file.getBytes(), fileName);

            // 将解析结果发送到消息队列进行后续处理
            ResumeParseMessage message = new ResumeParseMessage();
            Long userId = UserContext.getUser();
            message.setUserId(userId != null ? userId : 23L);
            message.setFileName(fileName);
            message.setFileContent(parseResult.getBytes());
            message.setFileType("application/json");
            message.setParseMode("image");
            String queue = "file_tran";
            rabbitTemplate.convertAndSend(queue, message);

            FileParseRes res = new FileParseRes();
            res.setStatus("processing");
            res.setParseJobId("img_" + System.currentTimeMillis());
            res.setPollAfterMs(2000);
            return Result.success(res);

        } catch (IOException e) {
            log.error("图片处理失败: {}", e.getMessage(), e);
            return Result.error(500, "图片处理失败: " + e.getMessage());
        }
    }

    /**
     * 判断是否为支持的图片类型
     */
    private boolean isImageFile(String contentType) {
        if (contentType == null) {
            return false;
        }
        return contentType.startsWith("image/") && (
                contentType.contains("jpeg") ||
                contentType.contains("jpg") ||
                contentType.contains("png") ||
                contentType.contains("gif") ||
                contentType.contains("bmp") ||
                contentType.contains("webp")
        );
    }

    //    更新学生简历并保存（不再自动触发 AI 评分，仅落库；一个学生可有多份简历，按 profileId 定位）
    @PostMapping("/users/me/profile")
    public Result saveProfile(@RequestBody Map<String, Object> requestMap) {
        try {
            // 获取 markdown 简历文本
            String content = (String) requestMap.get("content");
            if (content == null || content.trim().isEmpty()) {
                return Result.error(114, "content字段不能为空");
            }
            // 简历id：编辑已有简历时前端携带；新建简历前端会生成 UUID 一并带上，为空则走 MQ 落库时由 profile-service 自动生成
            String profileId = (String) requestMap.get("profileId");

            Long userIdLong = UserContext.getUser();
            String userId = userIdLong != null ? userIdLong.toString() : "111";
//            多简历下不能再调用 delete_profile 删除该用户全部简历，改为按 user_id+profile_id 落库时 upsert

            // 放到保存简历的消息队列，由 profile-service 异步落库
            String profile_que = "profile_storage";
            Map<String, Object> profile_msg = new HashMap<>();
            profile_msg.put("userId", userId);
            profile_msg.put("profileId", profileId);
            profile_msg.put("profileData", content);
            profile_msg.put("fileName", null);
            profile_msg.put("fileType", null);
            profile_msg.put("timestamp", System.currentTimeMillis());

            rabbitTemplate.convertAndSend(profile_que, profile_msg);
            log.info("成功发送给简历存储服务");

            Map<String, Object> resultMap = new HashMap<>();

// 添加各个字段（profileId 回显前端生成的简历id，便于前端定位新标签）
            resultMap.put("profileId", profileId != null && !profileId.trim().isEmpty() ? profileId : userId);
            resultMap.put("updatedAt", "2026-02-15T10:30:00+08:00");
            resultMap.put("analysisStatus", "succeeded");

// 对于 null 值，可以放 null 或者根据需要处理
            resultMap.put("scores", null);
            resultMap.put("evidence", null);
            resultMap.put("improvementSuggestions", null);
            return Result.success(resultMap, "保存成功");
        } catch (Exception e) {
            return Result.error(0,"处理失败: " + e.getMessage());
        }
    }
    //通过微服务调用路径实时评估
    @PostMapping("/eval/realTime")
    public String realTimeEval(@RequestBody String prompt) {

        log.info("接收到实时评估的请求");
        String res_json = ai_route_eval.realTimeEvaluate(prompt);
        return res_json;
    }

    //人岗多维度详细分析
    @PostMapping("/job/analyze")
    public String analyzeJobs(@RequestBody String request_json) {
        log.info("接收到人岗多维度详细分析的请求");
        String res_json = ai_route_eval.analyzeJobs(request_json);
        return res_json;
    }

    @PostMapping("/eval/deep")
    public String deepEval(@RequestBody String prompt){
        log.info("接收到深度评估的请求");
        String res_json = ai_route_eval.deepEvaluate(prompt);
        return res_json;
    }
//    生成生涯报告
    @PostMapping("/roadmapping/create")
    public String createRoadmapping(@RequestBody String request_json){
        log.info("接收到生成生涯报告的请求");
        String roadMapping = ai_road_mapping.create_road_mapping(request_json);
        return roadMapping;
    }
//    生涯报告润色
    @PostMapping("/roadmapping/polish")
    public String polishRoadmapping(@RequestBody String request_json){
        log.info("接收到生涯报告润色的请求");
        String roadMapping = ai_road_mapping.polish_road_mapping(request_json);
        return roadMapping;
    }




}





