package group.profileservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.*;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.dto.ResumeEvaluationResult;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 能力评分表实体类
 * 对应数据库表：scores
 */
@Data
@TableName(value = "ability_score", autoResultMap = true)  // autoResultMap必须为true才能处理TypeHandler
public class Evaluation {

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
     * 评分数据 - 使用JacksonTypeHandler自动进行JSON序列化/反序列化
     */
    @TableField(value = "scores_data", typeHandler = JacksonTypeHandler.class)
    private ResumeEvaluationResult scoresData;

    /**
     * 创建时间
     */
    @TableField(value = "created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField(value = "updated_at")
    private LocalDateTime updatedAt;
}
