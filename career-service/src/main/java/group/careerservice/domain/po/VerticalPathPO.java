package group.careerservice.domain.po;/* I love coding */

// VerticalPathPO.java
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.AllArgsConstructor;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.LocalDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@TableName(value="job_vertical_path", autoResultMap = true)
public class VerticalPathPO {
    //该映射类已废弃
    @TableId(type = IdType.AUTO)
    private Long id;

    @JsonProperty("jobName")
    @TableField("job_name")
    private String jobName;

    @JsonProperty("verticalPath")
    @TableField(value = "vertical_path", typeHandler = JacksonTypeHandler.class)
    private List<String> verticalPath;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
