package group.userservice.controller;/* I love coding */
import cn.hutool.core.bean.BeanUtil;
import group.userservice.domain.po.User_;
import group.userservice.domain.response.LoginRes;
import group.userservice.domain.response.UserInfo;
import group.userservice.domain.vo.UserVO;
import group.userservice.service.IUserService;
import lombok.AllArgsConstructor;
import lombok.Data;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import group.common.Result;

import java.util.HashMap;
import java.util.Map;


@RestController
@AllArgsConstructor
@RequestMapping("/users")
public class UserController {
    private IUserService userService;
    @PostMapping("/login")
    public Result<LoginRes> login(@RequestBody UserVO userVO) {
        LoginRes res = userService.login(userVO.getUserphone(), userVO.getPassword());
        if(res != null){
            return Result.success(res);
        }
        return Result.error(401,"用户名或密码错误");
    }

    @PostMapping("/register")
    public Result<Map<String, String>> register(@RequestBody UserVO userVO) {
        String userId = userService.register(userVO.getUserphone(), userVO.getPassword(), userVO.getUsername());
        Map<String, String> res = new HashMap<>();
        res.put("userId", userId);
        if(userId != null){
            return Result.success(res);
        }
        return Result.error(409,"手机号已被注册");
    }
}
