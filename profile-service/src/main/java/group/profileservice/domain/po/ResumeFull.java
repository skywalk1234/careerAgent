package group.profileservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.dto.StudentProfile;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "resume_full", autoResultMap = true)
public class ResumeFull {

    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField(value = "user_id")
    private Long user_id;

    @TableField(value = "resume_data", typeHandler = JacksonTypeHandler.class)
    private StudentProfile resumeData;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("created_at")
    private LocalDateTime createdAt = LocalDateTime.now();

    @TableField("updated_at")
    private LocalDateTime updatedAt = LocalDateTime.now();
}
