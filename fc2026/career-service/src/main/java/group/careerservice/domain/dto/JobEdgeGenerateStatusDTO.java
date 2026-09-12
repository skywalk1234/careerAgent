package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobEdgeGenerateStatusDTO {

    private String edgeJobId;

    private String status;

    private Integer errorCode;

    private String errorMessage;

    private EdgeResult result;

    @Data
    public static class EdgeResult {
        private Integer generatedEdges;
        private Integer promotionEdges;
        private Integer transitionEdges;
        private String updatedAt;
    }
}
