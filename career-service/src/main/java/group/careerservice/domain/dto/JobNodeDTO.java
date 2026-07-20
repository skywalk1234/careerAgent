package group.careerservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class JobNodeDTO {
    private String id;
    private JobDocument job;
    private String stage;
}
