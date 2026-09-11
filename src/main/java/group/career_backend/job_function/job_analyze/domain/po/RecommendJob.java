package group.career_backend.job_function.job_analyze.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import group.career_backend.job_function.job_analyze.domain.dto.MatchJob;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "recommend_jobs", autoResultMap = true)
public class RecommendJob {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("userId")
    private Long userId;

    @TableField(value = "matchResult", typeHandler = Jackson3TypeHandler.class)
    private MatchJob matchResult;

    @TableField("createdAt")
    private LocalDateTime createdAt;
}
