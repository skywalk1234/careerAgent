package group.careerservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class JobLikes {
    private String jobId;
    private String jobName;
    private String city;
    private String educationRequirement;
    private Boolean salaryNegotiable;
    private String salaryNormalized;
    private String updatedAtRaw;
    private String favoritedAt;
}
