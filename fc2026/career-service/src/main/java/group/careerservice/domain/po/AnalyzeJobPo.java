package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.careerservice.domain.dto.AnalyzeJobDTO;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "analyze_jobs", autoResultMap = true)
public class AnalyzeJobPo {
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;
    @TableField("user_id")
    private Long userId;
    @TableField("job_id")
    private String jobId;
    @TableField(value = "analysis", typeHandler = JacksonTypeHandler.class)
    private AnalyzeJobDTO analysis;
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
