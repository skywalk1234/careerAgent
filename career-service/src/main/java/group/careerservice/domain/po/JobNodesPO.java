package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.dto.JobNodes;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 作业节点表 实体类
 * </p>
 *
 * @author YourName
 * @since 2026-03-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "job_nodes", autoResultMap = true)
public class JobNodesPO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 节点ID
     */
    @TableField("node_id")
    private String nodeId;

    /**
     * 节点名称
     */
    @TableField("node_name")
    private String nodeName;

    /**
     * 规范作业ID
     */
    @TableField("canonical_job_id")
    private String canonicalJobId;

    /**
     * 元数据信息 (JSON类型)
     * 注意：具体类型可根据项目实际使用的JSON库调整，如 String, Map<String, Object>, 或 JsonNode
     * 此处按您的要求暂定为 Object，需配合相应的 TypeHandler 或使用 MyBatis-Plus 默认的 Jackson/Fastjson 处理
     */
    @TableField(value = "metadata", typeHandler = JacksonTypeHandler.class)
    private JobNodes metadata;

    /**
     * 创建时间
     */
    @TableField("created_at")
    private LocalDateTime createdAt;

    /**
     * 更新时间
     */
    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
