package group.careerservice.client;/* I love coding */

import group.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "profile-service")
public interface ProfileClient {
    //这个方法在profile-service中定义
    @GetMapping("/users/me/profile")
    public Result getProfile(@RequestParam String userId);
}
