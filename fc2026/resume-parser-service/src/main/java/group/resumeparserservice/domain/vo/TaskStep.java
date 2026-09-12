package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TaskStep implements java.io.Serializable {
    private String stepId;
    private String title;
    private String status;
    private String toolName;
    private String toolParams;
}
