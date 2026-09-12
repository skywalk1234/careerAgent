package group.career_backend.user.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
@TableName("`user`")
public class User_ {
    @TableId(value = "user_id", type = IdType.ASSIGN_ID)
    private Long userId;

    @TableField("username")
    private String username;

    @TableField("userphone")
    private String phone;

    @TableField("role")
    private String role;

    @TableField("password")
    private String password;

    @TableField("create_at")
    private LocalDateTime createAt;

    @TableField("update_at")
    private LocalDateTime updateAt;
}
