package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolResultEvent implements java.io.Serializable {
    private String taskId;
    private String stepId;
    private ToolResultInfo tool;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ToolResultInfo implements java.io.Serializable {
        private String name;
        private String resultSummary;
    }
}
