package group.careerservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LatestRouteDTO {
    private Boolean hasPath;
    private PathInfo latestPath;
    private Map<String,String> draft;// "draftId": "cp_draft_1"
    private String updatedAt;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PathInfo {
        private String pathId;
        private String pathName;
    }
}
