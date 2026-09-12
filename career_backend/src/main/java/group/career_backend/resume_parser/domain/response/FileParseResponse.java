package group.career_backend.resume_parser.domain.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class FileParseResponse {
    private String parseJobId;
    private String status;
    private Integer pollAfterMs;
}
