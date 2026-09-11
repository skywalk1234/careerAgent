package group.career_backend.user.controller;/* I love coding */

import group.career_backend.common.Result;
import group.career_backend.user.domain.response.LoginRes;
import group.career_backend.user.domain.vo.UserVO;
import group.career_backend.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@RequestMapping("/users")
public class UserController {
    private final IUserService userService;

    @PostMapping("/login")
    public Result<LoginRes> login(@RequestBody UserVO userVO) {
        LoginRes res = userService.login(userVO.getUserphone(), userVO.getPassword());
        if (res != null) {
            return Result.success(res);
        }
        return Result.error(401, "用户名或密码错误");
    }

    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody UserVO userVO) {
        String userId = userService.register(userVO.getUserphone(), userVO.getPassword(), userVO.getUsername());
        Map<String, String> res = new HashMap<>();
        res.put("userId", userId);
        if (userId != null) {
            return Result.success(res);
        }
        return Result.error(409, "手机号已被注册");
    }
}
