package group.career_backend.profile.controller;

import group.career_backend.profile.domain.dto.StudentProfile;
import group.career_backend.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class ProfileMessageController {
    private final ProfileService profileService;

    @RabbitListener(queues = "profile_storage")
    public void storeProfile(Map<String, Object> message) {
        Long userId = toLong(message.get("userId"));
        String profileId = (String) message.get("profileId");
        log.info("[MQ消息] 收到 profile_storage 消息, userId={}, profileId={}, fileName={}",
                userId, profileId, message.get("fileName"));
        if (userId == null) {
            log.error("[MQ消息] profile_storage 消息缺少 userId");
            throw new IllegalArgumentException("profile_storage message is missing userId");
        }

        log.info("[MQ消息] 开始组装并保存简历, userId={}, profileId={}", userId, profileId);
        StudentProfile profile = new StudentProfile();
        profile.setId(userId);
        profile.setContent((String) message.get("profileData"));
        profileService.saveProfile(
                profile,
                userId,
                profileId,
                (String) message.get("fileName"),
                (String) message.get("fileType"));
        log.info("[MQ消息] 简历保存完成, userId={}, profileId={}", userId, profile.getProfileId());
    }

    private Long toLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}
