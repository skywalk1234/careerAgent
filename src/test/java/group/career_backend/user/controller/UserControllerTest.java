package group.career_backend.user.controller;

import group.career_backend.security.JwtTool;
import group.career_backend.user.domain.response.LoginRes;
import group.career_backend.user.domain.response.UserInfo;
import group.career_backend.user.service.UserService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UserController.class)
class UserControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UserService userService;

    @MockitoBean
    private JwtTool jwtTool;

    @Test
    void loginSuccessKeepsOriginalContract() throws Exception {
        UserInfo userInfo = new UserInfo();
        userInfo.setUserId("123");
        userInfo.setUsername("张三");
        userInfo.setUserphone("13800138000");

        LoginRes loginRes = new LoginRes();
        loginRes.setToken("test-token");
        loginRes.setExpiresIn(100);
        loginRes.setUserInfo(userInfo);
        when(userService.login("13800138000", "123456")).thenReturn(loginRes);

        mockMvc.perform(post("/users/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userphone": "13800138000",
                                  "password": "123456",
                                  "autoLogin": true
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.msg").value("success"))
                .andExpect(jsonPath("$.data.token").value("test-token"))
                .andExpect(jsonPath("$.data.expiresIn").value(100))
                .andExpect(jsonPath("$.data.userInfo.userId").value("123"))
                .andExpect(jsonPath("$.data.userInfo.username").value("张三"))
                .andExpect(jsonPath("$.data.userInfo.userphone").value("13800138000"));
    }

    @Test
    void loginFailureUsesBodyCodeWithHttp200() throws Exception {
        when(userService.login("13800138000", "wrong")).thenReturn(null);

        mockMvc.perform(post("/users/login")
                        .contentType("application/json")
                        .content("""
                                {
                                  "userphone": "13800138000",
                                  "password": "wrong"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(401))
                .andExpect(jsonPath("$.msg").value("用户名或密码错误"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }

    @Test
    void registerSuccessKeepsOriginalContract() throws Exception {
        when(userService.register("13800138000", "123456", "张三")).thenReturn("123");

        mockMvc.perform(post("/users/register")
                        .contentType("application/json")
                        .content("""
                                {
                                  "username": "张三",
                                  "userphone": "13800138000",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.userId").value("123"));
    }

    @Test
    void duplicatePhoneUsesBodyCodeWithHttp200() throws Exception {
        when(userService.register("13800138000", "123456", "张三")).thenReturn(null);

        mockMvc.perform(post("/users/register")
                        .contentType("application/json")
                        .content("""
                                {
                                  "username": "张三",
                                  "userphone": "13800138000",
                                  "password": "123456"
                                }
                                """))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.code").value(409))
                .andExpect(jsonPath("$.msg").value("手机号已被注册"))
                .andExpect(jsonPath("$.data").doesNotExist());
    }
}
