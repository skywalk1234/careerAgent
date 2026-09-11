package group.career_backend.profile.domain.response;

import group.career_backend.profile.domain.dto.StudentProfile;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class QueryResumeResponse {
    private String parseJobId;
    private String status;
    private ParseResult result;

    @Data
    @AllArgsConstructor
    public static class ParseResult {
        private StudentProfile parsedProfile;
        private List<String> missingFields;
        private Map<String, String> sourceMeta;
    }
}
