package group.profileservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDate;

/**
 * 证书实体类
 */
@Data
@TableName("certificates") //该类废弃
public class Certificates {

    // 主键
    @TableId
    private Integer id;

    // 关联的用户ID
    private Integer userId;

    private String name;

    // 证书日期
    private String date;

    private String issuer;
}
