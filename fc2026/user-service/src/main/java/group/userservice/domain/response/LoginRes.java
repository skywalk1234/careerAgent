package group.userservice.domain.response;/* I love coding */

import lombok.Data;

@Data
public class LoginRes {
    /**
     * JWT Token
     */
    private String token;

    /**
     * Token 过期时间（秒）
     */
    private Integer expiresIn;

    /**
     * 用户信息
     */
    private UserInfo userInfo;
}
