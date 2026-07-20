package group.careerservice.domain.dto;

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
//ai推荐的岗位的结果
public class MatchJob implements Serializable {
    private BestMatch bestMatch;
    private List<Recommendation> otherRecommendations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BestMatch {
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

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        public static class DimensionScores {
            private int basicRequirement;
            private int professionalSkill;
            private int professionalLiteracy;
            private int developmentPotential;
        }
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Recommendation {
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
    }
}