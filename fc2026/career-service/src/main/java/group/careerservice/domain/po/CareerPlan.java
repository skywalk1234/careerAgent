package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 职业规划专家生成的行动方案（表 user_career_plans，落在 career-service 数据源连接的 MySQL）。
 * 设计见 fc2026/职业规划专家方案.md：用户可多次规划，新版本插入时旧版置 archived，不做逐条勾选。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_career_plans")
public class CareerPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID（硬隔离，绝不可少） */
    @TableField("user_id")
    private Long userId;

    /** 来源会话（溯源用，可空） */
    @TableField("session_id")
    private String sessionId;

    /** active / archived（同 user 最新一条 active = 当前方案） */
    @TableField("status")
    private String status;

    /** 方案标题（列表/入口展示） */
    @TableField("title")
    private String title;

    /** 方案 markdown 全文 */
    @TableField("content")
    private String content;

    /** 版本链：本方案取代的旧方案 id（可空） */
    @TableField("supersedes_plan_id")
    private Long supersedesPlanId;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
