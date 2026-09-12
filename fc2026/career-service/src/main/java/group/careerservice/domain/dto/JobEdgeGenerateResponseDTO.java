package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobEdgeGenerateResponseDTO {

    private String edgeJobId;

    private String status;

    private Long pollAfterMs;
}
