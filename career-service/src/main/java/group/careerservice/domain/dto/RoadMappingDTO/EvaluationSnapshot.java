package group.careerservice.domain.dto.RoadMappingDTO;/* I love coding */

import group.careerservice.domain.dto.DeepEvalInfoDTO;
import lombok.Data;
import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class EvaluationSnapshot implements Serializable {
    private Integer feasibilityScore;
    private Integer readinessScore;
    private Integer recommendationScore;
    private List<String> riskAlerts;
    private List<DeepEvalInfoDTO.SummaryMetric> summaryMetrics;
    private AbilityComparison abilityComparison;



    @Data
    public static class AbilityComparison implements Serializable {
        private List<Dimension> dimensions;

        @Data
        public static class Dimension implements Serializable {
            private String key;
            private String label;
            private Integer studentScore;
            private Integer targetRequiredScore;
        }
    }
}
