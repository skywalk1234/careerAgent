package group.userservice.service;/* I love coding */

import com.baomidou.mybatisplus.extension.service.IService;
import group.userservice.domain.po.User_;
import group.userservice.domain.response.LoginRes;
import group.userservice.domain.response.UserInfo;

public interface IUserService extends IService<User_> {
    public LoginRes login(String userphone, String password);
    public String register(String userphone, String password, String username);
}
