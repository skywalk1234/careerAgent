package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import group.careerservice.domain.dto.MatchJob;
import lombok.Data;
import org.bouncycastle.util.Times;

import java.sql.Timestamp;
import java.util.Date;
import java.util.regex.MatchResult;


/**
 * 匹配任务实体类
 */
@Data
@TableName(value="recommend_jobs", autoResultMap = true) // 请替换为实际的表名，如果表名与类名不一致需手动指定
public class MatchJobPo {

    /**
     * 主键ID，自增
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Integer id;

    /**
     * 用户ID
     */
    @TableField("userId")
    private Long userId;

    /**
     * 推荐结果，使用已创建好的MatchResult类
     */
    @TableField(value="matchResult", typeHandler = JacksonTypeHandler.class)
    private MatchJob matchResult;

    /**
     * 创建时间
     */
    @TableField("createdAt")
    private Timestamp createdAt;
}
