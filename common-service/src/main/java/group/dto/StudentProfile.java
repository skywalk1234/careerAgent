package group.dto;/* I love coding */

import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;

import java.io.Serializable;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StudentProfile implements Serializable {
    private Long id;//学生id，全局唯一，一个学生只有一个简历和画像
    private String content;//带 markdown 语法的简历原始文本，保留简历全部内容，便于后续润色优化
}
