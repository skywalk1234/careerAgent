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
        private String companyName;
        private String city;
        /** 学历要求（job_detail_vector.edu） */
        private String edu;
        /** 原始薪资文本，如 20-35K·15薪 */
        private String salaryText;
        private String updatedAtRaw;
        private String favoritedAt;
    }
}
