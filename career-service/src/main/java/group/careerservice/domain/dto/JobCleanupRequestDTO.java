package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobCleanupRequestDTO {

    private Integer expireDays;

    private Boolean dryRun;
}
