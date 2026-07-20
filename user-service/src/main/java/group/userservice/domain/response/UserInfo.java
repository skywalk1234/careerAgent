package group.userservice.domain.response;/* I love coding */

import lombok.Data;

@Data
public class UserInfo {
    /**
     * 用户ID
     */
    private String userId;

    /**
     * 用户名
     */
    private String username;

    /**
     * 用户手机号
     */
    private String userphone;
}
