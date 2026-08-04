package group.profileservice;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.ComponentScan;

@SpringBootApplication
// 必须扫描 common-service 的 group.config 才能注册 UserInfoInterceptor，
// 否则 UserContext 恒为 null，获取画像时 userId 会兜底成 "111"
@ComponentScan(basePackages = {"group.profileservice", "group.config", "group.interceptor", "group.utils"})
public class ProfileServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ProfileServiceApplication.class, args);
    }

}
