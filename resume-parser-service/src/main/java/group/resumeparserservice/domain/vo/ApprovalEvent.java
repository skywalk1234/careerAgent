package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ApprovalEvent implements java.io.Serializable {
    private String taskId;
    private String stepId;
    private ApprovalToolInfo tool;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class ApprovalToolInfo implements java.io.Serializable {
        private String name;
        private String params;
        private String approvalId;
    }
}
