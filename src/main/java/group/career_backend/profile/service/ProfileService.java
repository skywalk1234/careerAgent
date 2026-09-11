package group.career_backend.profile.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.career_backend.profile.domain.dto.OpenSourceBonus;
import group.career_backend.profile.domain.dto.StudentProfile;
import group.career_backend.profile.domain.po.ResumeFull;
import group.career_backend.profile.domain.response.GetProfileResponse;
import group.career_backend.profile.mapper.ResumeFullMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ProfileService {
    private final ResumeFullMapper resumeFullMapper;

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

    public ResumeFull getLatestResume(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .orderByDesc("updated_at")
                .last("LIMIT 1");
        return resumeFullMapper.selectOne(wrapper);
    }

    public ResumeFull getResume(Long userId, String profileId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .eq("profile_id", profileId)
                .last("LIMIT 1");
        return resumeFullMapper.selectOne(wrapper);
    }

    public List<ResumeFull> listResumes(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<ResumeFull>()
                .eq("user_id", userId)
                .orderByDesc("updated_at");
        return resumeFullMapper.selectList(wrapper);
    }

    public int deleteProfiles(Long userId) {
        return resumeFullMapper.delete(new QueryWrapper<ResumeFull>().eq("user_id", userId));
    }

    public GetProfileResponse getProfileResponse(Long userId) {
        ResumeFull resume = getLatestResume(userId);
        return buildProfileResponse(resume, userId.toString());
    }

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
}
