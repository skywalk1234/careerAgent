package group.careerservice.domain.response;/* I love coding */

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

@Data
public class JobsRes implements Serializable {

    @JsonProperty("jobId")
    private String jobId;

    @JsonProperty("jobName")
    private String jobName;

    @JsonProperty("jobCode")
    private String jobCode;

    @JsonProperty("companyName")
    private String companyName;

    @JsonProperty("city")
    private String city;

    @JsonProperty("district")
    private String district;

    @JsonProperty("industryTags")
    private List<String> industryTags;

    @JsonProperty("educationRequirement")
    private String educationRequirement;

    @JsonProperty("salaryMin")
    private Integer salaryMin;

    @JsonProperty("salaryMax")
    private Integer salaryMax;

    @JsonProperty("salaryUnit")
    private String salaryUnit;

    @JsonProperty("salaryMonths")
    private Integer salaryMonths;

    @JsonProperty("salaryNegotiable")
    private Boolean salaryNegotiable;

    @JsonProperty("salaryNormalized")
    private String salaryNormalized;

    @JsonProperty("level")
    private String level;

    @JsonProperty("updatedAtRaw")
    private String updatedAtRaw;

    @JsonProperty("updatedAtNormalized")
    @JsonFormat(pattern = "yyyy-MM-dd'T'HH:mm:ssXXX", timezone = "GMT+8")
    private String updatedAtNormalized;

    @JsonProperty("sourceSite")
    private String sourceSite;

    @JsonProperty("companySize")
    private String companySize;

    @JsonProperty("companyType")
    private String companyType;


}
