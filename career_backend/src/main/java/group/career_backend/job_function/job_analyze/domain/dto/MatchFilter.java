package group.career_backend.job_function.job_analyze.domain.dto;

import lombok.Data;

import java.io.Serializable;
import java.util.List;
import java.util.Map;

@Data
public class MatchFilter implements Serializable {
    private Integer topN;
    private Scope scope;

    @Data
    public static class Scope implements Serializable {
        private List<String> preferredJobIds;
        private List<String> preferredJobKeywords;
        private List<String> cityIntents;
        private List<String> benefits;
        private Map<String, Integer> salaryRange;
        private Boolean includeSimilarJobs;
    }
}
