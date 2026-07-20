package group.careerservice.domain.dto;

import lombok.Data;

@Data
public class JobCleanupResponseDTO {

    private Integer expireDays;

    private Integer removedCount;

    private Integer archiveCount;

    private String executedAt;
}
