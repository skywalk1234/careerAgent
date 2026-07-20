package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskArtifactsResponse implements java.io.Serializable {
    private String taskId;
    private Integer total;
    private List<ArtifactItem> list;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ArtifactItem implements java.io.Serializable {
        private String artifactId;
        private String type;
        private String title;
        private String content;
        private LocalDateTime createdAt;
    }
}
