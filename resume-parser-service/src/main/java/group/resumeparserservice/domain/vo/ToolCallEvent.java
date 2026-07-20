package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ToolCallEvent implements java.io.Serializable {
    private String taskId;
    private String stepId;
    private ToolInfo tool;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ToolInfo implements java.io.Serializable {
        private String name;
        private boolean requiresApproval;
    }
}
