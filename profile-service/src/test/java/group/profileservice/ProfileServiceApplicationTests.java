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
        System.out.println("学生姓名: " + studentProfile.getBasicInfo().getName());

        System.out.println("项目列表: " + studentProfile.getProjects());
        System.out.println("自我评价: " + studentProfile.getSelfEvaluation());
    }
    String json = "{\n" +
            "  \"basicInfo\": {\n" +
            "    \"name\": \"张三\",\n" +
            "    \"gender\": \"male\",\n" +
            "    \"birthday\": \"2003-06-01\",\n" +
            "    \"phone\": \"13800138000\",\n" +
            "    \"email\": \"zhangsan@example.com\",\n" +
            "    \"city\": \"西安\",\n" +
            "    \"jobIntention\": [\"前端开发工程师\"]\n" +
            "  },\n" +
            "  \"education\": [\n" +
            "    {\n" +
            "      \"school\": \"法外狂徒大学\",\n" +
            "      \"major\": \"软件工程\",\n" +
            "      \"degree\": \"本科\",\n" +
            "      \"startDate\": \"2022-09\",\n" +
            "      \"endDate\": \"2026-06\",\n" +
            "      \"gpa\": \"3.6/4.0\"\n" +
            "    }\n" +
            "  ],\n" +
            "  \"workExperience\": [\n" +
            "    {\n" +
            "      \"company\": \"XX科技有限公司\",\n" +
            "      \"position\": \"前端开发实习生\",\n" +
            "      \"startDate\": \"2025-07\",\n" +
            "      \"endDate\": \"2025-09\",\n" +
            "      \"description\": \"参与公司后台管理系统的前端开发与维护；使用 Vue3 + TypeScript + Element Plus 技术栈开发用户管理、数据统计等模块；负责页面组件的开发、测试与联调，确保功能稳定运行；优化前端性能，将页面加载速度提升20%；与后端开发人员紧密合作，实现前后端数据交互接口\"\n" +
            "    }\n" +
            "  ],\n" +
            "  \"skills\": [\n" +
            "    \"Vue 3\",\n" +
            "    \"Vue Router\",\n" +
            "    \"Pinia\",\n" +
            "    \"Vite\",\n" +
            "    \"TypeScript\",\n" +
            "    \"JavaScript (ES6+)\",\n" +
            "    \"HTML5\",\n" +
            "    \"CSS3\",\n" +
            "    \"Element Plus\",\n" +
            "    \"Ant Design Vue\",\n" +
            "    \"Tailwind CSS\",\n" +
            "    \"ECharts\",\n" +
            "    \"AntV G2\",\n" +
            "    \"Webpack\",\n" +
            "    \"Git\",\n" +
            "    \"GitHub\",\n" +
            "    \"GitLab\",\n" +
            "    \"响应式设计\",\n" +
            "    \"移动端适配\",\n" +
            "    \"RESTful API 设计与调用\",\n" +
            "    \"前端工程化\",\n" +
            "    \"模块化开发\",\n" +
            "    \"Agile/Scrum 开发流程\"\n" +
            "  ],\n" +
            "  \"certificates\": [\n" +
            "    {\n" +
            "      \"name\": \"英语六级（CET-6）\",\n" +
            "      \"date\": \"2024-12\",\n" +
            "      \"issuer\": \"教育部考试中心\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"校级优秀学生干部\",\n" +
            "      \"date\": \"2024-06\",\n" +
            "      \"issuer\": \"XX大学\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"前端开发工程师认证\",\n" +
            "      \"date\": \"2024-03\",\n" +
            "      \"issuer\": \"慕课网\"\n" +
            "    },\n" +
            "    {\n" +
            "      \"name\": \"Vue.js 高级开发者认证\",\n" +
            "      \"date\": \"2023-10\",\n" +
            "      \"issuer\": \"开源社区\"\n" +
            "    }\n" +
            "  ],\n" +
            "  \"organizeExp\": [\n" +
            "    \"学生会主席 | XX大学学生会 | 2023年9月 - 2024年9月：负责学生会日常工作的组织与协调；策划并执行校园年度音乐会，吸引观众800余人；协调10个社团参与演出，管理20人志愿者团队；通过活动筹款2万余元用于学生活动基金；代表学生参与学校管理决策会议；培养了优秀的组织协调能力和团队管理能力\",\n" +
            "    \"技术社区参与：定期在校园技术社区分享前端开发经验；参与 Element Plus 组件库的文档翻译工作；在个人博客撰写前端技术文章，累计阅读量10万+\"\n" +
            "  ],\n" +
            "  \"projects\": [],\n" +
            "  \"selfEvaluation\": \"学习能力强，善于沟通，有项目落地经验；技术学习：保持对前端技术的热情，持续学习新技术，每周投入15+小时进行技术钻研；沟通协作：具备良好的团队协作精神，能够清晰表达技术观点，积极参与团队讨论；项目落地：从需求分析到上线部署，具备完整的项目开发经验，注重代码质量和用户体验；问题解决：善于分析问题本质，能够独立解决复杂技术难题；责任心强：对工作认真负责，注重细节，追求代码的优雅与高效\"\n" +
            "}";

}
