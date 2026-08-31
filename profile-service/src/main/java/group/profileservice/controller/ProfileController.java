package group.profileservice.controller;/* I love coding */

import com.fasterxml.jackson.databind.ObjectMapper;
import group.common.Result;
import group.dto.ResumeEvaluationResult;
import group.dto.StudentProfile;
import group.profileservice.domain.po.Evaluation;
import group.dto.OpenSourceBonus;
import group.profileservice.domain.po.ResumeFull;
import group.dto.GetProfileResponse;
import group.profileservice.domain.response.QueryResumeRes;

import group.profileservice.domain.response.ResumeNullRes;
import group.profileservice.service.*;
import group.utils.UserContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
public class ProfileController {
    private final SaveProfileService saveProfileService;
    private final QueryResumeService queryResumeService;
    private final SaveEvalService saveEvalService;
    private final QueryProfileService queryProfileService;
    //保存简历
    @RabbitListener(queues = "profile_storage")
    public void listen_profile_exchange(Map<String, Object> profileMsg) {  // 直接接收对象
        String content = (String) profileMsg.get("profileData");

        //由于序列化过程可能导致long处理成integer，所以这里要判断
        Object userIdObj = profileMsg.get("userId");
        Long userId = null;

        if (userIdObj != null) {
            if (userIdObj instanceof Integer) {
                userId = ((Integer) userIdObj).longValue();  // Integer转Long
            } else if (userIdObj instanceof Long) {
                userId = (Long) userIdObj;  // 直接转换
            } else {
                // 如果是其他类型（如String）
                userId = Long.valueOf(userIdObj.toString());
            }
        }


        System.out.println("消费者接收到markdown内容");
        System.out.println("userid:  "+userId);
        System.out.println("fileName: "+ profileMsg.get("fileName"));
        System.out.println("fileType: "+ profileMsg.get("fileType"));
        // 前端手动保存会带 profileId（编辑已有简历）；上传解析等走 MQ 的路径没有则为空（落库时自动生成）
        String profileId = (String) profileMsg.get("profileId");
        System.out.println("profileId: "+ profileId);
        // 组装 StudentProfile（content 为带 markdown 语法的简历原始文本）
        StudentProfile studentProfile = new StudentProfile();
        studentProfile.setId(userId);
        studentProfile.setProfileId(profileId);
        studentProfile.setContent(content);
        // ... 处理业务逻辑
        saveProfileService.saveProfile(studentProfile, userId, profileId, (String)profileMsg.get("fileName"), (String)profileMsg.get("fileType"));
        System.out.println("整个简历markdown整理存储工作完成");


    }
    //查询简历解析任务是否完成
    @RequestMapping("/users/me/profile/parse-jobs/{parseJobId}")
    public Result queryResume(@PathVariable String parseJobId){
        ResumeFull resumeFull = queryResumeService.queryResume(Long.parseLong(parseJobId));
        //这里得改一改，改成符合相应格式
        if (resumeFull == null){
            return Result.success(new ResumeNullRes(parseJobId));
        }
        StudentProfile studentProfile = resumeFull.getResumeData();
        QueryResumeRes queryResumeRes = new QueryResumeRes();
        queryResumeRes.setParseJobId(parseJobId);
        queryResumeRes.setStatus("success");
        // 简历内容已统一为 markdown 文本，缺失检测只在内容为空时生效
        List<String> missing = new ArrayList<>();
        if (studentProfile == null || studentProfile.getContent() == null || studentProfile.getContent().trim().isEmpty()) {
            missing.add("content");
        }
        Map<String, String> sourceMeta = new HashMap<>();
        sourceMeta.put("fileName", resumeFull.getFileName());
        sourceMeta.put("fileType", resumeFull.getFileType());
        queryResumeRes.setResult(new QueryResumeRes.Result_(studentProfile, missing, sourceMeta));
        return Result.success(queryResumeRes);
    }
// 获取学生画像
    @GetMapping("/users/me/profile")
    public Result get_profile(@RequestParam(required = false) String userId) {
        if (userId == null) {
            Long userIdLong = UserContext.getUser();
            userId = userIdLong != null ? userIdLong.toString() : "111";
        }
        GetProfileResponse res = new GetProfileResponse();

        StudentProfile profile = queryProfileService.getProfile(Long.parseLong(userId));
        ResumeEvaluationResult eval_result = queryProfileService.getEvaluationResult(Long.parseLong(userId));

        res.setHasProfile(profile != null);
        res.setProfileId(userId);

        if (profile != null) {
            res.setProfile(profile);
        } else {
            res.setProfile(null);
        }

        if (eval_result != null) {
            res.setScores(eval_result.getScores());
            res.setEvidence(eval_result.getEvidence());
            res.setImprovementSuggestions(eval_result.getImprovementSuggestions());
        } else {
            // 如果eval_result为null，这些字段设置为null
            res.setScores(null);
            res.setEvidence(null);
            res.setImprovementSuggestions(null);
        }

        // 开源项目加分，该功能亟待开发
        OpenSourceBonus openSourceBonus = new OpenSourceBonus();  // 设置为null
        res.setOpenSourceBonus(openSourceBonus);
        res.setUpdatedAt(LocalDateTime.now().toString());

        return Result.success(res);
    }

    // 获取学生全部简历（一个学生多份简历，前端标签页用），最新的在前
    @GetMapping("/users/me/profile/list")
    public Result list_profile(@RequestParam(required = false) String userId) {
        if (userId == null) {
            Long userIdLong = UserContext.getUser();
            userId = userIdLong != null ? userIdLong.toString() : "111";
        }
        List<ResumeFull> resumeList = saveProfileService.listResumes(Long.parseLong(userId));
        List<Map<String, Object>> items = new ArrayList<>();
        for (ResumeFull resumeFull : resumeList) {
            Map<String, Object> item = new HashMap<>();
            item.put("profileId", resumeFull.getProfileId());
            StudentProfile profile = resumeFull.getResumeData();
            String content = profile != null ? profile.getContent() : null;
            item.put("content", content);
            item.put("title", buildResumeTitle(content));
            item.put("updatedAt", resumeFull.getUpdatedAt() != null ? resumeFull.getUpdatedAt().toString() : null);
            item.put("fileName", resumeFull.getFileName());
            item.put("fileType", resumeFull.getFileType());
            items.add(item);
        }
        return Result.success(items);
    }

    // 取简历第一行作为标签标题，去掉前导 markdown 标记并截断，避免标题过长
    private String buildResumeTitle(String content) {
        if (content == null || content.trim().isEmpty()) {
            return "未命名简历";
        }
        String firstLine = content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        if (firstLine.isEmpty()) {
            return "未命名简历";
        }
        String title = firstLine.replaceFirst("^[#>*_\\-\\s]+", "");
        if (title.isEmpty()) {
            title = firstLine;
        }
        return title.length() > 20 ? title.substring(0, 20) + "…" : title;
    }


//    保存评分
    @RabbitListener(queues = "eval_storage")
    public void resume_score(Map<String, Object> profileMsg){
        String eval_json = (String) profileMsg.get("profileData");

        Object userIdObj = profileMsg.get("userId");
        Long userId = null;

        if (userIdObj != null) {
            if (userIdObj instanceof Integer) {
                userId = ((Integer) userIdObj).longValue();  // Integer转Long
            } else if (userIdObj instanceof Long) {
                userId = (Long) userIdObj;  // 直接转换
            } else {
                // 如果是其他类型（如String）
                userId = Long.valueOf(userIdObj.toString());
            }
        }

        System.out.println("评分存储接收到json："+eval_json);
        System.out.println("userid:  "+userId);
        // 将json转为对象
        ObjectMapper objectMapper = new ObjectMapper();
        ResumeEvaluationResult eval_result = new ResumeEvaluationResult();
        try {
            eval_result = objectMapper.readValue(eval_json, ResumeEvaluationResult.class);
        }catch (Exception e){
            log.error("评分结果转对象失败", e);
            throw new RuntimeException(e);
        }
        System.out.println("评分结果："+eval_result.getEvidence());
        System.out.println("评分结果："+eval_result.getScores());
        System.out.println("评分结果："+eval_result.getImprovementSuggestions());
        saveEvalService.saveEval(eval_result, userId);



    }

//    查询评分任务是否完成(查询画像分析状态）
    @RequestMapping("/users/me/profile/analyze-jobs/{analyzeJobId}")
    public Result query_eval(@PathVariable String analyzeJobId){
        Evaluation evaluation = saveEvalService.getEval(Integer.parseInt(analyzeJobId));
        Map<String, Object> res = new HashMap<>();
        if (evaluation == null){
            res.put("analyzeJobId", analyzeJobId);
            res.put("status", "processing");
            res.put("progress", 65);
            res.put("pollAfterMs", 1200);
            return Result.success(res);
        }
        res.put("analyzeJobId", analyzeJobId);
        res.put("status", "succeeded");
        return Result.success(res);
    }

    //在解析简历以及评分之前都先调用这个接口删除原来的简历和评分
    @GetMapping("/users/me/profile/delete")
    public Result delete_profile(@RequestParam String userId) {

        Integer delete_eval = saveEvalService.deleteEval(Long.parseLong(userId));
        Integer delete_profile = saveProfileService.deleteProfile(Long.parseLong(userId));
        if (delete_eval == 1) log.info("删除用户 {} 的评分成功", userId);
        if (delete_profile == 1) log.info("删除用户 {} 的简历成功", userId);

        return Result.success();
    }

}
