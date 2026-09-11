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
        if (userId == null) {
            throw new IllegalArgumentException("profile_storage message is missing userId");
        }

        StudentProfile profile = new StudentProfile();
        profile.setId(userId);
        profile.setContent((String) message.get("profileData"));
        profileService.saveProfile(
                profile,
                userId,
                (String) message.get("profileId"),
                (String) message.get("fileName"),
                (String) message.get("fileType"));
        log.info("用户 {} 的简历已保存", userId);
    }

    private Long toLong(Object value) {
        return value == null ? null : Long.valueOf(value.toString());
    }
}
