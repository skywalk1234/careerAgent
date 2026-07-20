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
public class TaskControl implements java.io.Serializable {
    private String taskId;
    private String status;
    private String currentStepId;
    private String action;
    private LocalDateTime pausedAt;
    private LocalDateTime resumedAt;
    private LocalDateTime updatedAt;
}
