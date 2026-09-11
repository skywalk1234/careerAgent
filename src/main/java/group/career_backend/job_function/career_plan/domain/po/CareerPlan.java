package group.career_backend.job_function.career_plan.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@TableName("user_career_plans")
public class CareerPlan implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("session_id")
    private String sessionId;

    @TableField("status")
    private String status;

    @TableField("title")
    private String title;

    @TableField("content")
    private String content;

    @TableField("supersedes_plan_id")
    private Long supersedesPlanId;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
