package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskQueryResponse implements java.io.Serializable {
    private String taskId;
    private String status;
    private String goal;
    private String mode;
    private PlanVO plan;
    private Object pendingApproval;
    private Object result;
    private Object error;
    private LocalDateTime updatedAt;
}
