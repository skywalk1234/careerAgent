package group.career_backend.profile.domain.response;

import lombok.Getter;

@Getter
public class ResumeProcessingResponse {
    private final String parseJobId;
    private final String status = "processing";
    private final Integer progress = 99;
    private final Integer pollAfterMs = 2000;

    public ResumeProcessingResponse(String parseJobId) {
        this.parseJobId = parseJobId;
    }
}
