package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class AgentTaskVO implements java.io.Serializable {
    private String goal;
    private String mode;
    private String priority;
    private TaskContext context;
    private TaskConstraints constraints;

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TaskContext implements java.io.Serializable {
        private String routePath;
        private String pageTitle;
        private Map<String, Object> data;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @Builder
    public static class TaskConstraints implements java.io.Serializable {
        private Integer maxSteps;
        private Integer maxDurationSec;
        private String[] requireApprovalFor;
    }
}
