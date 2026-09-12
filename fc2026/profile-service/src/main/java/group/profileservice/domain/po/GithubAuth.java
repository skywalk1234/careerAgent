package group.profileservice.domain.po;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * GitHub授权信息实体类
 * 对应数据库表：github_auth
 */
@Data
@TableName("github_auth")
public class GithubAuth {

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * GitHub用户名
     */
    @TableField("account_name")
    private String accountName;

    /**
     * GitHub用户主页URL
     */
    @TableField("profile_url")
    private String profileUrl;

    /**
     * 访问令牌
     */
    @TableField("access_token")
    private String accessToken;

    /**
     * 贡献热力图数据(JSON格式存储)
     */
    @TableField("contribution_heatmap")
    private String contributionHeatmap;

    /**
     * 技术栈统计数据(JSON格式存储)
     */
    @TableField("language_stats")
    private String languageStats;

    /**
     * 加分详情(JSON格式存储)
     */
    @TableField("bonus_details")
    private String bonusDetails;

    /**
     * 总加分
     */
    @TableField("total_bonus")
    private Integer totalBonus;

    /**
     * 授权时间
     */
    @TableField("authorized_at")
    private LocalDateTime authorizedAt;

    /**
     * 创建时间
     */
    @TableField(value = "created_at", fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at", fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
