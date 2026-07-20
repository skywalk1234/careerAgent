package group.resumeparserservice.domain.dto;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ResumeParseMessage implements Serializable {
    private Long userId;
    private String fileName;
    private byte[] fileContent;  // 文件二进制内容
    private String fileType;      // pdf/docx
    private String parseMode;
}
