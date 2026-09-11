package group.career_backend.job_function.job_analyze.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonAlias;
import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class AnalyzeJobResult implements Serializable {
    private JobInfo job;
    @JsonAlias("Analysis")
    private AnalyzeInfo analysis;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JobInfo implements Serializable {
        private String jobId;
        private String jobName;
        private String companyName;
        private String city;
        private List<String> industryTags;
        private String educationRequirement;
        private Boolean salaryNegotiable;
        private String salaryNormalized;
        private String updatedAtRaw;
        private String level;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AnalyzeInfo implements Serializable {
        private Integer overallScore;
        private DimensionScores dimensionScores;
        private DimensionScores jobExpectedScores;
        private ScoreDetails studentAbilityScores;
        private ScoreDetails jobAbilityScores;
        private Map<String, DimensionAnalysis> dimensionAnalysis;
        private List<String> matchTags;
        private List<ImprovementSuggestion> improvementSuggestions;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DimensionScores implements Serializable {
        private Integer basicRequirement;
        private Integer professionalSkill;
        private Integer professionalLiteracy;
        private Integer developmentPotential;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ScoreDetails implements Serializable {
        private Integer professionalSkill;
        private Integer certificate;
        private Integer innovation;
        private Integer internalMotivation;
        private Integer learning;
        private Integer stressTolerance;
        private Integer communication;
        private Integer internship;
        private Integer language;
        private Integer leadership;
        private Integer adaptability;
        private Integer execution;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class DimensionAnalysis implements Serializable {
        private String label;
        private Integer score;
        private Integer expectedScore;
        private String reason;
        private String confidence;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ImprovementSuggestion implements Serializable {
        private String dimension;
        private String priority;
        private String advice;
    }
}
