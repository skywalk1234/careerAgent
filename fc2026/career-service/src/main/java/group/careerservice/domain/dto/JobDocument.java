package group.careerservice.domain.dto;/* I love coding */



import org.springframework.data.elasticsearch.annotations.FieldType;
import lombok.Data;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.Document;
import org.springframework.data.elasticsearch.annotations.Field;

import java.util.List;
import java.util.Map;

@Data
@Document(indexName = "jobs_index")
public class JobDocument {

    @Id
    private String jobId;

    @Field(type = FieldType.Text, analyzer = "chinese_analyzer")
    private String jobName;

    @Field(type = FieldType.Keyword)
    private String jobCode;

    @Field(type = FieldType.Text, analyzer = "chinese_analyzer")
    private String companyName;

    @Field(type = FieldType.Keyword)
    private String city;

    @Field(type = FieldType.Keyword)
    private String district;

//    @Field(type = FieldType.GeoPoint)
//    private GeoPoint location; // 可选：存储经纬度

    @Field(type = FieldType.Keyword)
    private List<String> industryTags;

    @Field(type = FieldType.Keyword)
    private String educationRequirement;

    @Field(type = FieldType.Integer)
    private Integer salaryMin;

    @Field(type = FieldType.Integer)
    private Integer salaryMax;

    @Field(type = FieldType.Keyword)
    private String salaryUnit;

    @Field(type = FieldType.Integer)
    private Integer salaryMonths;

    @Field(type = FieldType.Boolean)
    private Boolean salaryNegotiable;

    @Field(type = FieldType.Text)
    private String salaryNormalized;

    @Field(type = FieldType.Keyword)
    private String updatedAtRaw;

    @Field(type = FieldType.Keyword)
    private String updatedAtNormalized;

    @Field(type = FieldType.Keyword, index = false)
    private String sourceUrl;

    @Field(type = FieldType.Keyword)
    private String sourceSite;

    @Field(type = FieldType.Keyword)
    private String companySize;

    @Field(type = FieldType.Keyword)
    private String companyType;

    @Field(type = FieldType.Keyword)
    private String level;

    @Field(type = FieldType.Text, analyzer = "chinese_analyzer")
    private String jobDescription;

    @Field(type = FieldType.Text, analyzer = "chinese_analyzer")
    private String companyBrief;

    @Field(type = FieldType.Text, analyzer = "chinese_analyzer")
    private String companyDescription;

    @Field(type = FieldType.Nested)
    private AbilityRequirements abilityRequirements;

    @Field(type = FieldType.Object)
    private Map<String, String> dimensionDetails;

    @Field(type = FieldType.Keyword)
    private String createdAt;

    @Data
    public static class AbilityRequirements {
        public Integer professionalSkill;
        public Integer certificate;
        public Integer innovation;
        public Integer internalMotivation;
        public Integer learning;
        public Integer stressTolerance;
        public Integer communication;
        public Integer internship;
        public Integer language;
        public Integer leadership;
        public Integer adaptability;
        public Integer execution;
    }
}