package group.careerservice.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
// 忽略未声明字段：ProcessMatchFilterService 用裸 new ObjectMapper() 反序列化，
// FAIL_ON_UNKNOWN_PROPERTIES 默认为 true，多一个键就会抛 UnrecognizedPropertyException。
// 注解不继承，每个类都要单独标注。
@JsonIgnoreProperties(ignoreUnknown = true)
//ai推荐的岗位的结果
public class MatchJob implements Serializable {
    private BestMatch bestMatch;
    private List<Recommendation> otherRecommendations;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
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
        // AI 排序理由（Python 侧生成）
        private String reason;

        @Data
        @NoArgsConstructor
        @AllArgsConstructor
        @JsonIgnoreProperties(ignoreUnknown = true)
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
    @JsonIgnoreProperties(ignoreUnknown = true)
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
        // AI 排序理由（Python 侧生成）
        private String reason;
    }
}