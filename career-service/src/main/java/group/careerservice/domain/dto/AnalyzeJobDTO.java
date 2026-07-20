package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;

import java.util.List;
import java.util.Map;
//用于映射ai返回的匹配分析的字段
@Data
public class AnalyzeJobDTO {
    private JobInfo job;
    private AnalyzeInfo Analysis;

    @Data
    public static class JobInfo {
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
    public static class AnalyzeInfo {
        private Integer overallScore;
        private DimensionScores dimensionScores;
        private DimensionScores jobExpectedScores;
        private ScoreDetails studentAbilityScores;
        private ScoreDetails jobAbilityScores;
        private Map<String, DimensionAnalysis> dimensionAnalysis;
        private List<String> matchTags;
        private List<ImprovementSuggestion> improvementSuggestions;

        @Data
        public static class DimensionScores {
            private Integer basicRequirement;
            private Integer professionalSkill;
            private Integer professionalLiteracy;
            private Integer developmentPotential;
        }

        @Data
        public static class ScoreDetails {
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
        public static class DimensionAnalysis {
            private String label;
            private Integer score;
            private Integer expectedScore;
            private String reason;
            private String confidence;
        }

        @Data
        public static class ImprovementSuggestion {
            private String dimension;
            private String priority;
            private String advice;
        }
    }
}
