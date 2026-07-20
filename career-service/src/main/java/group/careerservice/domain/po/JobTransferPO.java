package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.dto.JobTransfer;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * <p>
 * 转移关系边表 实体类
 * </p>
 *
 * @author YourName
 * @since 2026-03-19
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "transfer_edges", autoResultMap = true)
public class JobTransferPO implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 主键ID
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 源节点标识
     */
    @TableField("source")
    private String source;

    /**
     * 目标节点标识
     */
    @TableField("target")
    private String target;

    /**
     * 转移元数据（如转移条件、耗时、类型等）
     * 类型定为 Object，具体序列化/反序列化依赖项目配置的 TypeHandler 或 MyBatis-Plus 默认行为
     */
    @TableField(value="metadata", typeHandler = JacksonTypeHandler.class)
    private JobTransfer metadata;

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
