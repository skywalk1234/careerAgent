package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.careerservice.domain.dto.DraftInfoDTO;
import lombok.Data;
import java.time.LocalDateTime;

@Data
@TableName(value="analysis_draft", autoResultMap = true)
public class AnalysisDraftPO {

    @TableId(type = IdType.AUTO)
    private Long id;
    private Long userId;

    private String draftId;

    @TableField(typeHandler = JacksonTypeHandler.class)
    private DraftInfoDTO draftInfo;

    @TableField("created_at")
    private LocalDateTime createdAt;
    
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
