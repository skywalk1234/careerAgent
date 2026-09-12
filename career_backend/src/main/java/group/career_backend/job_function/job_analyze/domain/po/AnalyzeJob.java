package group.career_backend.job_function.job_analyze.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import group.career_backend.job_function.job_analyze.domain.dto.AnalyzeJobResult;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "analyze_jobs", autoResultMap = true)
public class AnalyzeJob {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("job_id")
    private String jobId;

    @TableField(value = "analysis", typeHandler = Jackson3TypeHandler.class)
    private AnalyzeJobResult analysis;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
