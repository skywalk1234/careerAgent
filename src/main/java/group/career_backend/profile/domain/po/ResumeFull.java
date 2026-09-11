package group.career_backend.profile.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.Jackson3TypeHandler;
import group.career_backend.profile.domain.dto.StudentProfile;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName(value = "resume_full", autoResultMap = true)
public class ResumeFull {
    @TableId(value = "id", type = IdType.INPUT)
    private Long id;

    @TableField("user_id")
    private Long userId;

    @TableField("profile_id")
    private String profileId;

    @TableField(value = "resume_data", typeHandler = Jackson3TypeHandler.class)
    private StudentProfile resumeData;

    @TableField("file_name")
    private String fileName;

    @TableField("file_type")
    private String fileType;

    @TableField("created_at")
    private LocalDateTime createdAt;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
