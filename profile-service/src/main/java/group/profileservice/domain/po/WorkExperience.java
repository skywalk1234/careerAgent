package group.profileservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 工作经历实体类
 */
@Data
@TableName("workExperience")//该类废弃
public class WorkExperience {

    // 主键
    @TableId
    private Integer id;

    // 关联的用户ID
    private Integer userId;

    private String company;
    private String position;

    // 开始日期
    @TableField("startDate")
    private String startDate;

    @TableField("endDate")

    private String endDate;

    // 工作描述
    private String description;
}
