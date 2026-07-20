package group.profileservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

@Data
@TableName("education") //该类废弃
public class Education {

    // 主键
    @TableId
    private Long id;

    // 关联的用户ID
    private Integer userId;

    private String school;
    private String major;
    private String degree;

    @TableField("startDate")
    private String startDate;
    @TableField("endDate")
    private String endDate;
    private String gpa;
}
