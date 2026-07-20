package group.dto;/* I love coding */

import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class GetProfileResponse {
    private boolean hasProfile;
    private String profileId;
    private StudentProfile profile;
    private ResumeEvaluationResult.Scores scores;
    private Map<String, List<String>> evidence;
    private List<ResumeEvaluationResult.ImprovementSuggestion> improvementSuggestions;
    private OpenSourceBonus openSourceBonus;
    private String updatedAt;
}
