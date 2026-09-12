package group.career_backend.profile.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentProfile implements Serializable {
    private Long id;
    private String profileId;
    private String content;
}
