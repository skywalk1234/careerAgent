package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("favorite_jobs")
public class FavoriteJob implements Serializable {

    private static final long serialVersionUID = 1L;

    /**
     * 自增主键
     */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /**
     * 用户ID
     */
    @TableField("user_id")
    private Long userId;

    /**
     * 岗位ID（字符串格式）
     */
    @TableField("job_id")
    private String jobId;

    /**
     * 收藏时间
     */
    @TableField(value = "created_at")
    private LocalDateTime FavoriteAt;



}
