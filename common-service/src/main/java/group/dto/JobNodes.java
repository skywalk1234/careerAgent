package group.dto;/* I love coding */

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobNodes {
    @JsonProperty("nodeId")
    private String nodeId;

    @JsonProperty("canonicalJobId")
    private String canonicalJobId;

    @JsonProperty("nodeName")
    private String nodeName;

    @JsonProperty("jobFamily")
    private String jobFamily;

    @JsonProperty("jobFamilyLabel")
    private String jobFamilyLabel;

    @JsonProperty("level")
    private String level;

//    @JsonProperty("industryTags")
//    private List<String> industryTags;

    // 核心能力要求 (用于过滤或展示，不作为主要向量内容，除非你想按能力搜)
    @JsonProperty("abilityRequirements")
    private Map<String, Integer> abilityRequirements;

    @JsonProperty("coreSkills")
    private List<String> coreSkills;

    @JsonProperty("tools")
    private List<String> tools;

    @JsonProperty("frameworks")
    private List<String> frameworks;

    @JsonProperty("languages")
    private List<String> languages;

    @JsonProperty("summary")
    private String summary;

    @JsonProperty("requirements")
    private Requirements requirements;

    @Data
    public static class Requirements {
        @JsonProperty("education")
        private String education;
        @JsonProperty("experienceYears")
        private String experienceYears;
        @JsonProperty("major")
        private List<String> major;
        @JsonProperty("hardRequirements")
        private List<String> hardRequirements;
        @JsonProperty("certificates")
        private List<String> certificates;
    }
}
