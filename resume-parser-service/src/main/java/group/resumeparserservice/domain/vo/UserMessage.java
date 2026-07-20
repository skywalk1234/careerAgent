package group.resumeparserservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@Builder
public class UserMessage implements Serializable {
    private String messageId;
    private String role;
    private String content;
    private String status;
    private LocalDateTime createdAt;
}
