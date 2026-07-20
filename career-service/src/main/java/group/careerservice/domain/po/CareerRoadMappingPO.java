package group.careerservice.domain.po;/* I love coding */


import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import com.fasterxml.jackson.annotation.JsonFormat;
import group.careerservice.domain.dto.RoadMappingDTO.*;
import lombok.*;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 职业路径映射表实体类
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@Builder
@NoArgsConstructor  // 添加无参构造
@AllArgsConstructor // 添加全参构造（可选）
@TableName(value = "career_report", autoResultMap = true)
public class CareerRoadMappingPO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("user_id")
    private String userId;
    /**
     * 报告任务ID
     */
    @TableField("report_job_id")
    private String reportJobId;

    /**
     * 报告ID
     */
    @TableField("report_id")
    private String reportId;

    @TableField("polish_job_id")
    private String polishJobId;

    /**
     * 报告标题
     */
    private String reportTitle;

    /**
     * 模板版本
     */
    private String templateVersion;

    /**
     * 状态
     */
    private String status;

    /**
     * 路径参考信息
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private PathRef pathRef;

    /**
     * 档案快照
     */
    @TableField(value="profile_snapshot",typeHandler = JacksonTypeHandler.class)
    private ProfileSnapshot profileSnapshot;

    /**
     * 评估快照
     */
    @TableField(value="evaluation_snapshot",typeHandler = JacksonTypeHandler.class)
    private EvaluationSnapshot evaluationSnapShot;

    /**
     * 报告章节
     */
    @TableField(value="report_sections",typeHandler = JacksonTypeHandler.class)
    private ReportSections reportSections;

    /**
     * 编辑元数据
     */
    @TableField(typeHandler = JacksonTypeHandler.class)
    private EditingMeta editingMeta;

    /**
     * 创建时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime generatedAt;

    /**
     * 更新时间
     */
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updatedAt;
}
