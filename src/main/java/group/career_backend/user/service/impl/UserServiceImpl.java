package group.career_backend.user.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import group.career_backend.exception.RegisterException;
import group.career_backend.security.JwtTool;
import group.career_backend.user.domain.po.User_;
import group.career_backend.user.domain.response.LoginRes;
import group.career_backend.user.domain.response.UserInfo;
import group.career_backend.user.mapper.UserMapper;
import group.career_backend.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
@Slf4j
public class UserServiceImpl extends ServiceImpl<UserMapper, User_> implements UserService {
    private final JwtTool jwtTool;

    @Override
    public LoginRes login(String userphone, String password) {
        log.info("[业务处理] 开始查询登录用户");
        User_ user = lambdaQuery()
                .eq(User_::getPhone, userphone)
                .eq(User_::getPassword, password)
                .one();
        if (user == null) {
            log.warn("[业务处理] 登录用户不存在或密码错误");
            return null;
        }

        Long id = user.getUserId();
        log.info("[业务处理] 用户验证通过，开始生成令牌, userId={}", id);
        String token = jwtTool.createToken(id, Duration.ofDays(1));

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(id.toString());
        userInfo.setUsername(user.getUsername());
        userInfo.setUserphone(user.getPhone());

        LoginRes res = new LoginRes();
        res.setToken(token);
        res.setUserInfo(userInfo);
        res.setExpiresIn(100);
        log.info("[业务处理] 登录结果组装完成, userId={}", id);
        return res;
    }

    @Override
    public String register(String userphone, String password, String username) {
        log.info("[业务处理] 开始检查注册手机号是否可用");
        if (!checkPhoneUnique(userphone)) {
            log.warn("[业务处理] 注册手机号已存在");
            return null;
        }

        log.info("[业务处理] 手机号可用，开始创建用户");
        User_ user = new User_();
        user.setPhone(userphone);
        user.setPassword(password);
        user.setUsername(username);
        user.setRole("student");
        user.setCreateAt(LocalDateTime.now());
        user.setUpdateAt(LocalDateTime.now());

        boolean success = save(user);
        if (!success) {
            log.error("[业务处理] 用户数据保存失败");
            throw new RegisterException("用户注册失败");
        }
        log.info("[业务处理] 用户数据保存成功, userId={}", user.getUserId());
        return user.getUserId().toString();
    }

    private boolean checkPhoneUnique(String phone) {
        LambdaQueryWrapper<User_> lambdaQuery = new LambdaQueryWrapper<>();
        lambdaQuery.eq(User_::getPhone, phone);
        return count(lambdaQuery) == 0;
    }
}
