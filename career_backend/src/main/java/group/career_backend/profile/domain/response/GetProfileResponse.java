package group.career_backend.profile.domain.response;

import group.career_backend.profile.domain.dto.OpenSourceBonus;
import group.career_backend.profile.domain.dto.StudentProfile;
import lombok.Data;

@Data
public class GetProfileResponse {
    private boolean hasProfile;
    private String profileId;
    private StudentProfile profile;
    private Object scores;
    private Object evidence;
    private Object improvementSuggestions;
    private OpenSourceBonus openSourceBonus;
    private String updatedAt;
}
