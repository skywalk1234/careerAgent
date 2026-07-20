package group.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * 简历评估结果类
 * 对应你提供的JSON结构
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeEvaluationResult {

    /**
     * 评分部分
     */
    private Scores scores;

    /**
     * 证据部分
     */
    private Map<String, List<String>> evidence;

    /**
     * 改进建议
     */
    private List<ImprovementSuggestion> improvementSuggestions;


    /**
     * 分数详情类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Scores {
        /**
         * 完整度分数
         */
        private Integer completenessScore;

        /**
         * 竞争力分数
         */
        private Integer competitivenessScore;

        /**
         * 12个维度的能力分数
         */
        private AbilityScores abilityScores;

        /**
         * 各维度加分项
         */
        private AbilityScores bonusByDimension;

    }

    /**
     * 12个维度的能力分数类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AbilityScores {
        public Integer professionalSkill;    // 专业技能
        public Integer certificate;          // 证书能力
        public Integer innovation;           // 创新能力
        public Integer internalMotivation;   // 内驱动力
        public Integer learning;             // 学习能力
        public Integer stressTolerance;      // 抗压能力
        public Integer communication;        // 沟通能力
        public Integer internship;           // 实习能力
        public Integer language;             // 语言能力
        public Integer leadership;           // 领导能力
        public Integer adaptability;         // 适应能力
        public Integer execution;            // 执行能力
    }

    /**
     * 改进建议类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ImprovementSuggestion {
        /**
         * 维度名称
         */
        private String dimension;

        /**
         * 优先级：high/medium/low
         */
        private String priority;

        /**
         * 具体建议
         */
        private String advice;
    }
}