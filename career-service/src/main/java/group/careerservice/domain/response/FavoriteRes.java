package group.careerservice.domain.response;/* I love coding */

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
        private String city;
        private String educationRequirement;
        private Boolean salaryNegotiable;
        private String salaryNormalized;
        private String updatedAtRaw;
        private String favoritedAt;
    }
}
