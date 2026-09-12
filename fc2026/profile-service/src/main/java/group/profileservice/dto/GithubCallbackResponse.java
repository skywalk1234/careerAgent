package group.profileservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * GitHub授权回调响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubCallbackResponse {

    /**
     * 是否已授权
     */
    private Boolean authorized;

    /**
     * 提供商
     */
    private String provider;

    /**
     * GitHub账号名
     */
    private String accountName;

    /**
     * 贡献热力图
     */
    private ContributionHeatmap contributionHeatmap;

    /**
     * 技术栈统计
     */
    private List<LanguageStat> languageStats;

    /**
     * 加分详情
     */
    private List<BonusDetail> bonusDetails;

    /**
     * 总加分
     */
    private Integer totalBonus;

    /**
     * 备注说明
     */
    private String note;

    /**
     * 贡献热力图内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ContributionHeatmap {
        private List<DayContribution> days;
    }

    /**
     * 每日贡献内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DayContribution {
        private String date;
        private Integer count;
    }

    /**
     * 技术栈统计内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class LanguageStat {
        private String name;
        private Integer value;
    }

    /**
     * 加分详情内部类
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class BonusDetail {
        private String dimension;
        private Integer delta;
        private String reason;
    }
}
