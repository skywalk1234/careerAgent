package group.resumeparserservice;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;
import org.springframework.context.annotation.ComponentScan;


@EnableFeignClients
@SpringBootApplication(exclude = {
//         排除语音合成 (TTS)
        DashScopeAudioSpeechAutoConfiguration.class,
        // 排除语音转录/识别 (STT) - 解决你当前的报错
        DashScopeAudioTranscriptionAutoConfiguration.class
})
// 必须扫描 common-service 的 group.config 才能注册 UserInfoInterceptor，
// 否则 UserContext 恒为 null，上传简历时 userId 会兜底成 23
@ComponentScan(basePackages = {"group.resumeparserservice", "group.config", "group.interceptor", "group.utils"})
public class ResumeParserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeParserServiceApplication.class, args);
    }

}
