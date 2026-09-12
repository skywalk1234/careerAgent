package group.career_backend.job_function.job_analyze.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class MatchJob implements Serializable {
    private BestMatch bestMatch;
    private List<Recommendation> otherRecommendations;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class BestMatch implements Serializable {
        private String jobId;
        private String jobName;
        private String companyName;
        private String city;
        private String educationRequirement;
        private boolean salaryNegotiable;
        private String salaryNormalized;
        private String updatedAtRaw;
        private String level;
        private int overallScore;
        private List<String> matchTags;
        private DimensionScores dimensionScores;
        private String reason;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DimensionScores implements Serializable {
        private int basicRequirement;
        private int professionalSkill;
        private int professionalLiteracy;
        private int developmentPotential;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Recommendation implements Serializable {
        private String jobId;
        private String jobName;
        private String companyName;
        private String city;
        private String educationRequirement;
        private boolean salaryNegotiable;
        private String salaryNormalized;
        private String updatedAtRaw;
        private String level;
        private int overallScore;
        private List<String> matchTags;
        private String reason;
    }
}
