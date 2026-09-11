package group.career_backend.resume_parser.controller;

import group.career_backend.resume_parser.domain.dto.ResumeParseMessage;
import group.career_backend.resume_parser.service.ResumeParseTaskService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class ResumeParseMessageController {
    private final ResumeParseTaskService resumeParseTaskService;

    @RabbitListener(queues = "file_tran")
    public void parseResume(ResumeParseMessage message) {
        log.info("[MQ消息] 收到 file_tran 消息, userId={}, fileName={}, parseMode={}",
                message.getUserId(), message.getFileName(), message.getParseMode());
        try {
            resumeParseTaskService.parse(message);
            log.info("[MQ消息] file_tran 消息处理完成, userId={}, fileName={}",
                    message.getUserId(), message.getFileName());
        } catch (RuntimeException exception) {
            log.error("[MQ消息] file_tran 消息处理失败, userId={}, fileName={}",
                    message.getUserId(), message.getFileName(), exception);
            throw exception;
        }
    }
}
