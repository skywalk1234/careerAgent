package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;
import java.util.List;

@Data
public class DeepEvalInfoDTO {
    private List<PathEdge> pathEdges;
    private Evaluation evaluation;

    @Data
    public static class PathEdge {
        private String source;
        private String target;
        private String relationType;
        private Double similarity;
        private String difficulty;
        private String reason;
        private Boolean inferred;
    }

    @Data
    public static class Evaluation {
        private String level;
        private Integer feasibilityScore;
        private Integer readinessScore;
        private Integer recommendationScore;
        private List<String> riskAlerts; // 由于文档中riskAlerts为空数组，先使用Object。可根据实际结构替换为具体类型，例如 List<RiskAlert>
        private String aiCommentary;
        private List<StagePlan> stagePlans;
        private List<SummaryMetric> summaryMetrics;
    }

    @Data
    public static class StagePlan {
        private String stage;
        private String stageLabel;
        private String cycle;
        private List<String> goals;
        private List<SuggestedTask> suggestedTasks;
        private List<String> metrics;
    }

    @Data
    public static class SuggestedTask {
        private String title;
        private String linkText;
        private String linkUrl;
    }

    @Data
    public static class SummaryMetric {
        private String key;
        private String label;
        private Double value;
    }
}
