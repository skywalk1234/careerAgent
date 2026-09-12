package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName("job_relations")
public class JobRelationPO {
    //该映射类已废弃
    @TableId(type = IdType.AUTO)
    private Long id;

    /**
     * 源岗位名称（已处理格式）
     */
    private String source;

    /**
     * 目标岗位名称（已处理格式）
     */
    private String target;

    /**
     * 相似度
     */
    @TableField("similarity")
    private Double similarity;

    /**
     * 难度等级
     */
    private String difficulty;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;

    /**
     * 仅用于处理的临时字段（不存储到数据库）
     */
    @TableField(exist = false)
    private transient String originalSource;

    @TableField(exist = false)
    private transient String originalTarget;
}