package group.career_backend.job_function.job_explore.domain.response;

import lombok.Data;

import java.util.List;

@Data
public class FavoriteRes {
    private Integer total;
    private List<JobInfo> list;

    @Data
    public static class JobInfo {
        private String jobId;
        private String jobName;
        private String companyName;
        private String city;
        private String edu;
        private String salaryText;
        private String updatedAtRaw;
        private String favoritedAt;
    }
}
