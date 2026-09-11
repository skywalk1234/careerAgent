package group.career_backend.user.service.impl;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.spring.service.impl.ServiceImpl;
import group.career_backend.exception.RegisterException;
import group.career_backend.security.JwtTool;
import group.career_backend.user.domain.po.User_;
import group.career_backend.user.domain.response.LoginRes;
import group.career_backend.user.domain.response.UserInfo;
import group.career_backend.user.mapper.UserMapper;
import group.career_backend.user.service.IUserService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;

@Service
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User_> implements IUserService {
    private final JwtTool jwtTool;

    @Override
    public LoginRes login(String userphone, String password) {
        User_ user = lambdaQuery()
                .eq(User_::getPhone, userphone)
                .eq(User_::getPassword, password)
                .one();
        if (user == null) {
            return null;
        }

        Long id = user.getUserId();
        String token = jwtTool.createToken(id, Duration.ofDays(1));

        UserInfo userInfo = new UserInfo();
        userInfo.setUserId(id.toString());
        userInfo.setUsername(user.getUsername());
        userInfo.setUserphone(user.getPhone());

        LoginRes res = new LoginRes();
        res.setToken(token);
        res.setUserInfo(userInfo);
        res.setExpiresIn(100);
        return res;
    }

    @Override
    public String register(String userphone, String password, String username) {
        if (!checkPhoneUnique(userphone)) {
            return null;
        }

        User_ user = new User_();
        user.setPhone(userphone);
        user.setPassword(password);
        user.setUsername(username);
        user.setRole("student");
        user.setCreateAt(LocalDateTime.now());
        user.setUpdateAt(LocalDateTime.now());

        boolean success = save(user);
        if (!success) {
            throw new RegisterException("用户注册失败");
        }
        return user.getUserId().toString();
    }

    private boolean checkPhoneUnique(String phone) {
        LambdaQueryWrapper<User_> lambdaQuery = new LambdaQueryWrapper<>();
        lambdaQuery.eq(User_::getPhone, phone);
        return count(lambdaQuery) == 0;
    }
}
