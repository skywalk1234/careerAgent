package group.career_backend.hello.controller;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@Slf4j
public class HelloController {

    @GetMapping("/hello")
    public String hello() {
        log.info("[接口访问] GET /hello");
        log.info("[接口完成] GET /hello");
        return "Hello World!";
    }
}
