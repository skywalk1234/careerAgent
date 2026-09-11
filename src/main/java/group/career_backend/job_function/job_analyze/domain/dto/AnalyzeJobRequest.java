package group.career_backend.job_function.job_analyze.domain.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AnalyzeJobRequest {
    private String jobId;
    private String source;
    private Boolean saveToHistory;
    private Boolean overwriteSameJob;
    private Map<String, String> sourceMeta;
}
