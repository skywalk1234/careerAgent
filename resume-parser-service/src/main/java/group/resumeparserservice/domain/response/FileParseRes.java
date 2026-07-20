package group.resumeparserservice.domain.response;/* I love coding */

import lombok.Data;

@Data
public class FileParseRes {
    private String parseJobId;
    private String status;
    private Integer pollAfterMs;
}
