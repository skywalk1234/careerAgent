package group.career_backend.profile.service;

import group.career_backend.profile.domain.dto.StudentProfile;
import group.career_backend.profile.domain.po.ResumeFull;
import group.career_backend.profile.domain.response.GetProfileResponse;

import java.util.List;
import java.util.Map;

public interface ProfileService {
    void saveProfile(StudentProfile profile, Long userId, String profileId, String fileName, String fileType);

    Object queryResume(Long userId, String parseJobId);

    ResumeFull getLatestResume(Long userId);

    ResumeFull getResume(Long userId, String profileId);

    List<ResumeFull> listResumes(Long userId);

    List<Map<String, Object>> listProfileItems(Long userId);

    int deleteProfiles(Long userId);

    GetProfileResponse getProfileResponse(Long userId);

    GetProfileResponse getProfileResponse(Long userId, String profileId);
}
