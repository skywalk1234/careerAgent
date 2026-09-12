package group.profileservice.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub授权URL响应DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class GithubAuthUrlResponse {

    /**
     * 提供商
     */
    private String provider;

    /**
     * 状态码
     */
    private String state;

    /**
     * 授权URL
     */
    private String authUrl;
}
