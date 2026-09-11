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
public class ProfileServiceImpl implements ProfileService {
    private final ResumeFullMapper resumeFullMapper;

    @Override
    public void saveProfile(StudentProfile profile, Long userId, String profileId,
                            String fileName, String fileType) {
        LocalDateTime now = LocalDateTime.now();
        ResumeFull resume = new ResumeFull();
        resume.setUserId(userId);
        resume.setFileName(fileName);
        resume.setFileType(fileType);
        resume.setUpdatedAt(now);

        if (!StringUtils.hasText(profileId)) {
            profileId = UUID.randomUUID().toString();
        }
        profile.setProfileId(profileId);
        resume.setProfileId(profileId);
        resume.setResumeData(profile);

        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .eq("profile_id", profileId);
        if (resumeFullMapper.exists(wrapper)) {
            resumeFullMapper.update(resume, wrapper);
            return;
        }

        resume.setCreatedAt(now);
        resumeFullMapper.insert(resume);
    }

    @Override
    public Object queryResume(Long userId, String parseJobId) {
        ResumeFull resume = getLatestResume(userId);
        if (resume == null) {
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
        return response;
    }

    @Override
    public ResumeFull getLatestResume(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        return resumeFullMapper.selectOne(wrapper);
    }

    @Override
    public ResumeFull getResume(Long userId, String profileId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .eq("profile_id", profileId)
                .last("LIMIT 1");
        return resumeFullMapper.selectOne(wrapper);
    }

    @Override
    public List<ResumeFull> listResumes(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .orderByDesc("updated_at");
        return resumeFullMapper.selectList(wrapper);
    }

    @Override
    public List<Map<String, Object>> listProfileItems(Long userId) {
        return listResumes(userId).stream().map(this::toListItem).toList();
    }

    @Override
    public int deleteProfiles(Long userId) {
        return resumeFullMapper.delete(new QueryWrapper<ResumeFull>().eq("user_id", userId));
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
