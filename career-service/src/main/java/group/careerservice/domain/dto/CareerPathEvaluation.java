package group.careerservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.util.List;

/**
 * 职业路径评估结果映射类
 */
@Data
@AllArgsConstructor
@RequiredArgsConstructor
public class CareerPathEvaluation {

    /**
     * 路径边列表
     */
    private List<PathEdge> pathEdges;

    /**
     * 综合评估信息
     */
    private Evaluation evaluation;

    /**
     * 路径边
     */
    @Data
    public static class PathEdge {
        /**
         * 源节点ID
         */
        private String source;

        /**
         * 目标节点ID
         */
        private String target;

        /**
         * 关系类型：promotion(晋升)/sideways(平调)/transition(转岗)/regression(降级)
         */
        private String relationType;

        /**
         * 相似度 (0-1之间)
         */
        private Double similarity;

        /**
         * 难度等级：low/medium/high
         */
        private String difficulty;

        /**
         * 原因描述
         */
        private String reason;

        /**
         * 是否为推断结果
         */
        private Boolean inferred;
    }

    /**
     * 综合评估
     */
    @Data
    public static class Evaluation {
        /**
         * 评估等级：light/medium/heavy
         */
        private String level;

        /**
         * 可行性分数 (0-100)
         */
        private Integer feasibilityScore;

        /**
         * 就绪度分数 (0-100)
         */
        private Integer readinessScore;

        /**
         * 推荐分数 (0-100)
         */
        private Integer recommendationScore;

        /**
         * 风险提示列表
         */
        private List<String> riskAlerts;

        /**
         * AI评语
         */
        private String aiCommentary;

        /**
         * 汇总指标
         */
        private List<SummaryMetric> summaryMetrics;
    }

    /**
     * 汇总指标
     */
    @Data
    public static class SummaryMetric {
        /**
         * 指标键
         */
        private String key;

        /**
         * 指标标签
         */
        private String label;

        /**
         * 指标值
         */
        private Number value; // 使用Number类型，可以接受Integer或Double
    }
}