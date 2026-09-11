package group.career_backend.user.controller;

import group.career_backend.common.Result;
import group.career_backend.user.domain.response.LoginRes;
import group.career_backend.user.domain.vo.UserVO;
import group.career_backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequiredArgsConstructor
@Slf4j
@RequestMapping("/users")
public class UserController {
    private final UserService userService;

    @PostMapping("/login")
    public Result<LoginRes> login(@RequestBody UserVO userVO) {
        log.info("[接口访问] POST /users/login");
        LoginRes res = userService.login(userVO.getUserphone(), userVO.getPassword());
        if (res != null) {
            log.info("[接口完成] POST /users/login, 登录成功, userId={}", res.getUserInfo().getUserId());
            return Result.success(res);
        }
        log.warn("[接口完成] POST /users/login, 用户名或密码错误");
        return Result.error(401, "用户名或密码错误");
    }

    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody UserVO userVO) {
        log.info("[接口访问] POST /users/register");
        String userId = userService.register(userVO.getUserphone(), userVO.getPassword(), userVO.getUsername());
        Map<String, String> res = new HashMap<>();
        res.put("userId", userId);
        if (userId != null) {
            log.info("[接口完成] POST /users/register, 注册成功, userId={}", userId);
            return Result.success(res);
        }
        log.warn("[接口完成] POST /users/register, 手机号已被注册");
        return Result.error(409, "手机号已被注册");
    }
}
