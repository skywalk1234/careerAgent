package group.dto;/* I love coding */

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.util.List;

/**
 * 作业推广推荐结果 DTO
 * 映射前端或算法服务返回的 JSON 结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 忽略 JSON 中多余的字段，增强兼容性
public class JobPromotion implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 源节点标识 (例如: "role:cj_backend_DataAlgo")
     */
    @JsonProperty("source")
    private String source;

    /**
     * 目标节点标识 (例如: "role:cj_backend_Java__3")
     */
    @JsonProperty("target")
    private String target;

    /**
     * 推荐得分 (例如: 0.6825)
     */
    @JsonProperty("score")
    private Double score;

    /**
     * 推荐理由详情对象
     */
    @JsonProperty("reason")
    private ReasonInfo reason;

    /**
     * 内部静态类：映射 reason 对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ReasonInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 层级匹配度 (例如: 1.0)
         */
        @JsonProperty("levelFit")
        private Double levelFit;

        /**
         * 技能继承度 (例如: 0.475)
         */
        @JsonProperty("skillInheritance")
        private Double skillInheritance;

        /**
         * 能力成长度 (例如: 0.8)
         */
        @JsonProperty("abilityGrowth")
        private Double abilityGrowth;

        /**
         * 领域连续性 (例如: 0.4)
         */
        @JsonProperty("domainContinuity")
        private Double domainContinuity;

        /**
         * 缺失的技能列表
         */
        @JsonProperty("missingSkills")
        private List<String> missingSkills;

        /**
         * 缺失的能力列表
         */
        @JsonProperty("missingAbilities")
        private List<String> missingAbilities;

        /**
         * 详细原因描述
         */
        @JsonProperty("why")
        private String why;
    }
}
