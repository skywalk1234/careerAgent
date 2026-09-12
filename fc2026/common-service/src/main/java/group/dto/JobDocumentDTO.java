package group.dto;

import lombok.Data;
import java.util.List;
import java.util.Map;

@Data
public class JobDocumentDTO {
    private String jobId;
    private String jobName;
    private String jobCode;
    private String companyName;
    private String city;
    private String district;
    private List<String> industryTags;
    private String educationRequirement;
    private Double salaryMin;
    private Double salaryMax;
    private String salaryUnit;
    private Integer salaryMonths;
    private Boolean salaryNegotiable;
    private String salaryNormalized;
    private String updatedAtRaw;
    private String updatedAtNormalized;
    private String sourceUrl;
    private String sourceSite;
    private String companySize;
    private String companyType;
    private String level;
    private String jobDescription;
    private String companyBrief;
    private String companyDescription;
    private AbilityRequirements abilityRequirements;
    private Map<String, String> dimensionDetails;

    @Data
    public static class AbilityRequirements {
        private Integer professionalSkill;
        private Integer certificate;
        private Integer innovation;
        private Integer internalMotivation;
        private Integer learning;
        private Integer stressTolerance;
        private Integer communication;
        private Integer internship;
        private Integer language;
        private Integer leadership;
        private Integer adaptability;
        private Integer execution;
    }
}