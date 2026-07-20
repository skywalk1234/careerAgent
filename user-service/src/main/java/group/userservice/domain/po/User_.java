package group.userservice.domain.po;/* I love coding */

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
    @TableId
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

    public String toString(){
        return "id:"+userId+" phone:"+phone+" password:"+password;
    }
}
