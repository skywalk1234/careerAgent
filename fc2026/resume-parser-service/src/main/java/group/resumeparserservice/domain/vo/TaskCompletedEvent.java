package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskCompletedEvent implements java.io.Serializable {
    private String taskId;
    private TaskResult result;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TaskResult implements java.io.Serializable {
        private String summary;
        private List<Artifact> artifacts;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class Artifact implements java.io.Serializable {
        private String artifactId;
        private String type;
    }
}
