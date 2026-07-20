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
 * 作业转移推荐结果 DTO
 * 映射转岗/迁移场景下的 JSON 结构
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true) // 忽略未知字段，增强兼容性
public class JobTransfer implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 源节点标识
     */
    @JsonProperty("source")
    private String source;

    /**
     * 目标节点标识
     */
    @JsonProperty("target")
    private String target;

    /**
     * 转移匹配得分
     */
    @JsonProperty("score")
    private Double score;

    /**
     * 转移理由详情对象
     */
    @JsonProperty("reason")
    private TransferReasonInfo reason;

    /**
     * 内部静态类：映射 transfer 场景下的 reason 对象
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TransferReasonInfo implements Serializable {

        private static final long serialVersionUID = 1L;

        /**
         * 桥接技能列表 (可用于平滑过渡的技能)
         */
        @JsonProperty("bridgeSkills")
        private List<String> bridgeSkills;

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
         * 差距成本 (数值型，表示转移的难度代价)
         */
        @JsonProperty("gapCost")
        private Double gapCost;

        /**
         * 预估难度 (文本描述，如 "高", "中", "低")
         */
        @JsonProperty("estimatedDifficulty")
        private String estimatedDifficulty;

        /**
         * 详细原因描述
         */
        @JsonProperty("why")
        private String why;
    }
}
