package group.career_backend.profile.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.career_backend.profile.domain.dto.OpenSourceBonus;
import group.career_backend.profile.domain.dto.StudentProfile;
import group.career_backend.profile.domain.po.ResumeFull;
import group.career_backend.profile.domain.response.GetProfileResponse;
import group.career_backend.profile.domain.response.QueryResumeResponse;
import group.career_backend.profile.domain.response.ResumeProcessingResponse;
import group.career_backend.profile.mapper.ResumeFullMapper;
import group.career_backend.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProfileServiceImpl implements ProfileService {
    private final ResumeFullMapper resumeFullMapper;

    @Override
    public void saveProfile(StudentProfile profile, Long userId, String profileId,
                            String fileName, String fileType) {
        log.info("[业务处理] 开始保存简历, userId={}, profileId={}, fileName={}, fileType={}",
                userId, profileId, fileName, fileType);
        LocalDateTime now = LocalDateTime.now();
        ResumeFull resume = new ResumeFull();
        resume.setUserId(userId);
        resume.setFileName(fileName);
        resume.setFileType(fileType);
        resume.setUpdatedAt(now);

        if (!StringUtils.hasText(profileId)) {
            profileId = UUID.randomUUID().toString();
            log.info("[业务处理] 已生成新简历ID, userId={}, profileId={}", userId, profileId);
        }
        profile.setProfileId(profileId);
        resume.setProfileId(profileId);
        resume.setResumeData(profile);

        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .eq("profile_id", profileId);
        if (resumeFullMapper.exists(wrapper)) {
            log.info("[业务处理] 简历已存在，执行更新, userId={}, profileId={}", userId, profileId);
            resumeFullMapper.update(resume, wrapper);
            log.info("[业务处理] 简历更新完成, userId={}, profileId={}", userId, profileId);
            return;
        }

        log.info("[业务处理] 简历不存在，执行新增, userId={}, profileId={}", userId, profileId);
        resume.setCreatedAt(now);
        resumeFullMapper.insert(resume);
        log.info("[业务处理] 简历新增完成, userId={}, profileId={}", userId, profileId);
    }

    @Override
    public Object queryResume(Long userId, String parseJobId) {
        log.info("[业务处理] 开始查询简历解析结果, userId={}, parseJobId={}", userId, parseJobId);
        ResumeFull resume = getLatestResume(userId);
        if (resume == null) {
            log.info("[业务处理] 暂未查询到解析结果，返回处理中, userId={}, parseJobId={}", userId, parseJobId);
            return new ResumeProcessingResponse(parseJobId);
        }

        StudentProfile profile = resume.getResumeData();
        List<String> missingFields = new ArrayList<>();
        if (profile == null || !StringUtils.hasText(profile.getContent())) {
            missingFields.add("content");
        }
        Map<String, String> sourceMeta = new HashMap<>();
        sourceMeta.put("fileName", resume.getFileName());
        sourceMeta.put("fileType", resume.getFileType());

        QueryResumeResponse response = new QueryResumeResponse();
        response.setParseJobId(parseJobId);
        response.setStatus("success");
        response.setResult(new QueryResumeResponse.ParseResult(profile, missingFields, sourceMeta));
        log.info("[业务处理] 简历解析结果组装完成, userId={}, parseJobId={}, missingFields={}",
                userId, parseJobId, missingFields);
        return response;
    }

    @Override
    public ResumeFull getLatestResume(Long userId) {
        log.info("[业务处理] 查询用户最新简历, userId={}", userId);
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        ResumeFull resume = resumeFullMapper.selectOne(wrapper);
        log.info("[业务处理] 最新简历查询完成, userId={}, found={}", userId, resume != null);
        return resume;
    }

    @Override
    public ResumeFull getResume(Long userId, String profileId) {
        log.info("[业务处理] 查询指定简历, userId={}, profileId={}", userId, profileId);
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .eq("profile_id", profileId)
                .last("LIMIT 1");
        ResumeFull resume = resumeFullMapper.selectOne(wrapper);
        log.info("[业务处理] 指定简历查询完成, userId={}, profileId={}, found={}",
                userId, profileId, resume != null);
        return resume;
    }

    @Override
    public List<ResumeFull> listResumes(Long userId) {
        log.info("[业务处理] 查询用户简历列表, userId={}", userId);
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .orderByDesc("updated_at");
        List<ResumeFull> resumes = resumeFullMapper.selectList(wrapper);
        log.info("[业务处理] 用户简历列表查询完成, userId={}, count={}", userId, resumes.size());
        return resumes;
    }

    @Override
    public List<Map<String, Object>> listProfileItems(Long userId) {
        return listResumes(userId).stream().map(this::toListItem).toList();
    }

    @Override
    public int deleteProfile(Long userId, String profileId) {
        log.info("[业务处理] 开始删除指定简历, userId={}, profileId={}", userId, profileId);
        if (!StringUtils.hasText(profileId)) {
            return 0;
        }
        int deleted = resumeFullMapper.delete(new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .eq("profile_id", profileId));
        log.info("[业务处理] 指定简历删除完成, userId={}, profileId={}, deleted={}", userId, profileId, deleted);
        return deleted;
    }

    @Override
    public GetProfileResponse getProfileResponse(Long userId) {
        return buildProfileResponse(getLatestResume(userId), userId.toString());
    }

    @Override
    public GetProfileResponse getProfileResponse(Long userId, String profileId) {
        return buildProfileResponse(getResume(userId, profileId), profileId);
    }

    private GetProfileResponse buildProfileResponse(ResumeFull resume, String profileId) {
        GetProfileResponse response = new GetProfileResponse();
        response.setHasProfile(resume != null);
        response.setProfileId(profileId);
        response.setProfile(resume == null ? null : resume.getResumeData());
        response.setScores(null);
        response.setEvidence(null);
        response.setImprovementSuggestions(null);
        response.setOpenSourceBonus(new OpenSourceBonus());
        response.setUpdatedAt(LocalDateTime.now().toString());
        return response;
    }

    private Map<String, Object> toListItem(ResumeFull resume) {
        Map<String, Object> item = new HashMap<>();
        StudentProfile profile = resume.getResumeData();
        String content = profile == null ? null : profile.getContent();
        item.put("profileId", resume.getProfileId());
        item.put("content", content);
        item.put("title", buildResumeTitle(content));
        item.put("updatedAt", resume.getUpdatedAt() == null ? null : resume.getUpdatedAt().toString());
        item.put("fileName", resume.getFileName());
        item.put("fileType", resume.getFileType());
        return item;
    }

    private String buildResumeTitle(String content) {
        if (!StringUtils.hasText(content)) {
            return "未命名简历";
        }
        String firstLine = content.lines()
                .map(String::trim)
                .filter(line -> !line.isEmpty())
                .findFirst()
                .orElse("");
        String title = firstLine.replaceFirst("^[#>*_\\-\\s]+", "");
        if (title.isEmpty()) {
            title = firstLine;
        }
        return title.length() > 20 ? title.substring(0, 20) + "…" : title;
    }
}
