package group.resumeparserservice.client;/* I love coding */

import group.common.Result;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;

@FeignClient(name = "profile-service")
public interface Profile_client {
    @GetMapping("/users/me/profile/delete")
    public Result delete_profile(@RequestParam String userId);

    @GetMapping("/users/me/profile")
    public Result get_profile(@RequestParam(required = false) String userId);


}

