package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.careerservice.domain.dto.DeepEvalInfoDTO;
import group.careerservice.domain.vo.SimpleRouteVO;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.sql.Timestamp;
import java.util.List;

/**
 * 对应数据库表：deep_eval（需根据实际表名调整@TableName）
 * 用于MyBatis-Plus直接写入数据库
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName(value="deep_eval_route", autoResultMap = true) // 替换为实际表名（如 deep_eval_path）
public class DeepEvalPO {

    /**
     * 主键ID（自增）
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 用户ID（大整数）
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 路径ID（字符串）
     */
    @TableField("path_id")
    private String pathId;

    /**
     * 收藏岗位ID（字符串）
     */
    @TableField("save_job_id")
    private String saveJobId;

    /**
     * 路径名称（字符串）
     */
    @TableField("path_name")
    private String pathName;

    /**
     * 深度评估信息（JSON格式，需配置MyBatis-Plus JSON类型处理器）
     */
    @TableField(value = "deep_eval_info", typeHandler = JacksonTypeHandler.class)
    private DeepEvalInfoDTO deepEvalInfo;

    @TableField(value = "path_nodes", typeHandler = JacksonTypeHandler.class)
    private List<SimpleRouteVO.PathNode> pathNodes;

    /**
     * 路径节点数（整数）
     */
    @TableField("path_node_count")
    private Integer pathNodeCount;

    /**
     * 可行性分数（整数）
     */
    @TableField("feasibilityScore")
    private Integer feasibilityScore;

    /**
     * 更新时间（时间戳）
     */
    @TableField("updatedAt")
    private Timestamp updatedAt;

}
