package group.careerservice.domain.response;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobGraphRes {

    private List<Node> nodes;
    private List<Edge> edges;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Node {
        private String jobName;
        private List<String> industryTags;
        private String jobDescription;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Edge {
        private String source;
        private String target;
        private Double similarity;
        private String difficulty;
        private String relationType;
        private String reason;
    }
}
