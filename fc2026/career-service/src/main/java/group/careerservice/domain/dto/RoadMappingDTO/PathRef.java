package group.careerservice.domain.dto.RoadMappingDTO;/* I love coding */

import lombok.Data;
import java.io.Serializable;

@Data
public class PathRef implements Serializable {
    private String pathId;
    private String pathName;
    private Integer pathNodeCount;
    private String targetJobId;
    private String targetJobName;
}
