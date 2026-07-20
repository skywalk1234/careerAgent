package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobEdgeGenerateRequestDTO {

    private String batchId;

    private EdgeStrategy strategy;

    @Data
    public static class EdgeStrategy {
        private Double minSimilarity;
        private Boolean includePromotion;
        private Boolean includeTransition;
        private Integer maxOutDegree;
    }
}
