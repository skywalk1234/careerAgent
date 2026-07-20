package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobImportResponseDTO {

    private String importJobId;

    private String status;

    private Long pollAfterMs;
}
