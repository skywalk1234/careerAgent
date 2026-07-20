package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskControlEvent implements java.io.Serializable {
    private String taskId;
    private String action;
    private String status;
}
