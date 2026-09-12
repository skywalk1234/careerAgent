package group.careerservice.domain.dto.RoadMappingDTO;/* I love coding */

import lombok.Data;
import java.io.Serializable;
import java.util.List;

@Data
public class ProfileSnapshot implements Serializable {
    private String profileId;
    private String name;
    private String major;
    private String city;
    private List<String> jobIntention;
    private Integer completenessScore;
    private Integer competitivenessScore;
}
