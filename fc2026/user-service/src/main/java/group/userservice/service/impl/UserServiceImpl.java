package group.userservice.service.impl;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;

import group.exception.RegisterException;
import group.userservice.domain.po.User_;
import group.userservice.domain.response.LoginRes;
import group.userservice.domain.response.UserInfo;
import group.userservice.jwt.JwtTool;
import group.userservice.mapper.UserMapper;
import group.userservice.service.IUserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.security.KeyPair;
import java.time.Duration;
import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserServiceImpl extends ServiceImpl<UserMapper, User_> implements IUserService {
    private final KeyPair keyPair;
    @Override
    public LoginRes login(String userphone, String password) {

        User_ user = lambdaQuery()
                .eq(User_::getPhone, userphone)
                .eq(User_::getPassword, password)
                .one();
        if (user == null) {
            return null;
        }
        log.info("user:{}",user);
        Long id = user.getUserId();
        System.out.println("user-id: "+ id);
        JwtTool jwtTool = new JwtTool(keyPair);
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

        // 2. 创建用户对象
        User_ user = new User_();
        user.setPhone(userphone);
        user.setPassword(password); // 注意：实际项目中密码需要加密
        user.setUsername(username);
        user.setRole("student"); // 默认角色
        user.setCreateAt(LocalDateTime.now());
        user.setUpdateAt(LocalDateTime.now());

        // 3. 保存到数据库
        boolean success = save(user);
        if (!success) {
            throw new RegisterException("用户注册失败");
        }
        return user.getUserId().toString();
    }

    private boolean checkPhoneUnique(String phone) {

        // 方式2：使用LambdaQueryWrapper（推荐）
        LambdaQueryWrapper<User_> lambdaQuery = new LambdaQueryWrapper<>();
        lambdaQuery.eq(User_::getPhone, phone);

        // 查询是否存在该手机号的用户
        long count = count(lambdaQuery);

        if (count > 0) {
            return false;
        }
        return true;
    }
}
