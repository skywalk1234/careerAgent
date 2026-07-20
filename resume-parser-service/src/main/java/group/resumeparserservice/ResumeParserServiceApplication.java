package group.resumeparserservice;

import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioSpeechAutoConfiguration;
import com.alibaba.cloud.ai.autoconfigure.dashscope.DashScopeAudioTranscriptionAutoConfiguration;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cloud.openfeign.EnableFeignClients;


@EnableFeignClients
@SpringBootApplication(exclude = {
//         排除语音合成 (TTS)
        DashScopeAudioSpeechAutoConfiguration.class,
        // 排除语音转录/识别 (STT) - 解决你当前的报错
        DashScopeAudioTranscriptionAutoConfiguration.class
})
public class ResumeParserServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(ResumeParserServiceApplication.class, args);
    }

}
