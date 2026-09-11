package group.career_backend.resume_parser.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeParseMessage implements Serializable {
    private Long userId;
    private String fileName;
    private byte[] fileContent;
    private String fileType;
    private String parseMode;
}
