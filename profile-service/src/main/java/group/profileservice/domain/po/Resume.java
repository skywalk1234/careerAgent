package group.profileservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 简历实体类
 */
@Data
@TableName(value = "resume", autoResultMap = true) //该类废弃
public class Resume {

    // 主键
    @TableId
    private Integer userId;

    private String name;
    private String phone;
    private String email;
    private String city;
    // JSON字段：求职意向
    @TableField(value = "job_intention", typeHandler = JacksonTypeHandler.class)
    private List<String> jobIntention;

    // JSON字段：技能
    @TableField(value = "skills", typeHandler = JacksonTypeHandler.class)
    private List<String> skills;

    // JSON字段：组织经历
    @TableField(value = "organize_exp", typeHandler = JacksonTypeHandler.class)
    private List<String> organizeExp;

    // JSON字段：项目经历
    @TableField(value = "projects", typeHandler = JacksonTypeHandler.class)
    private List<String> projects;

    // 文本字段：自我评价
    @TableField("self_evaluation")
    private String selfEvaluation;

    // 时间字段
    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
