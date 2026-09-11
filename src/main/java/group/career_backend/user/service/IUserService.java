package group.career_backend.user.service;/* I love coding */

import com.baomidou.mybatisplus.spring.service.IService;
import group.career_backend.user.domain.po.User_;
import group.career_backend.user.domain.response.LoginRes;

public interface IUserService extends IService<User_> {
    LoginRes login(String userphone, String password);

    String register(String userphone, String password, String username);
}
