package group.careerservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PathListDTO {
    private String pathId;
    private String pathName;
    private Integer pathNodeCount;
    private String targetJobName;
    private Integer feasibilityScore;
    private String updatedAt;
}
