package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Date;
import java.util.List;

@Data
@AllArgsConstructor
@Builder
public class AssistantMessage {
    private String messageId;
    private String role;
    private String content;
    private String status;
    private LocalDateTime createdAt;
    private List<Object> actions;
    private AgentTrace agentTrace;

    @Data
    @AllArgsConstructor
    @Builder
    public static class AgentTrace {
        private String traceVersion;
        private String mode;
        private String status;
        private String activeStepId;
        private List<Object> steps;
        private List<Object> tasks;
    }
}
