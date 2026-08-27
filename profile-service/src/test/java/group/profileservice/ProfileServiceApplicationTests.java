package group.profileservice;

import com.fasterxml.jackson.databind.ObjectMapper;
import group.dto.StudentProfile;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class ProfileServiceApplicationTests {

    @Test
    void contextLoads() {
    }

    @Test
    void test_json() throws Exception{
        ObjectMapper objectMapper = new ObjectMapper();
        StudentProfile studentProfile = objectMapper.readValue(json, StudentProfile.class);

        // 验证映射结果
        System.out.println("简历id: " + studentProfile.getId());
        System.out.println("简历markdown内容: " + studentProfile.getContent());
    }
    String json = "{\n" +
            "  \"id\": 10,\n" +
            "  \"content\": \"## 张三\\n\\n**电话**：13800138000\\n\\n### 教育背景\\n- 法外狂徒大学 | 软件工程 | 本科\\n\"\n" +
            "}";

}
