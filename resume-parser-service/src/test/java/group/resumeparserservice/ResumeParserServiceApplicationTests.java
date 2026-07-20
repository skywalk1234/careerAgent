package group.resumeparserservice;

import group.dto.StudentProfile;
import group.resumeparserservice.service.AI_score;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@SpringBootTest
@Slf4j
class ResumeParserServiceApplicationTests {
    @Autowired
    private RabbitTemplate rabbitTemplate;

    @Autowired
    private AI_score ai_score;
    @Test
    void contextLoads() {
    }

    @Test
    void testRabbitMQ() {
        StudentProfile stu_profile = new StudentProfile();
        //模拟解析完成
        stu_profile.setBasicInfo(new StudentProfile.BasicInfo("张三", "男", "1990-01-01", "1234567890", "zhangsan@163.com", "上海", List.of("软件工程")));
        stu_profile.setEducation(List.of(new StudentProfile.Education("上海交通大学", "软件工程", "本科", "2010-09-01", "2014-06-01", "3.8")));
        stu_profile.setWorkExperience(List.of(new StudentProfile.WorkExperience("上海交通大学", "软件工程", "2014-09-01", "2018-06-01", "上海交通大学")));
        stu_profile.setSkills(List.of("Java", "C++", "Python"));
        stu_profile.setCertificates(List.of(new StudentProfile.Certificate("计算机等级考试", "2014-09-01", "上海交通大学")));
        stu_profile.setOrganizeExp(List.of("上海交通大学"));
        stu_profile.setProjects(List.of("上海交通大学"));
        stu_profile.setSelfEvaluation("我叫张三，我是一个好学生。");
        String exchangeName = "profile";
        rabbitTemplate.convertAndSend(exchangeName, "", stu_profile);
    }


    @Test
    void testAIEvaluate() {
        Map<String, Object> profile_msg = new HashMap<>();
        profile_msg.put("userId", 886);
        String evaluation_json = "评估结果……";
        try{
            evaluation_json = ai_score.resume_score(res_json);
        }catch (Exception e){
            log.error("AI评分失败", e);
        }
        String score_que = "eval_storage";
        System.out.println("================================");
        System.out.println(evaluation_json);
        System.out.println("================================");
        if (evaluation_json != null) {
            profile_msg.put("profileData", evaluation_json);
            rabbitTemplate.convertAndSend(score_que, profile_msg);
            log.info("成功发送给评分存储服务");
        }


    }

    public static String res_json = """
            "parsedProfile": {
                           "id": 10,
                           "basicInfo": {
                               "name": "张三",
                               "gender": "male",
                               "birthday": "2003-06-01",
                               "phone": "13800138000",
                               "email": "zhangsan@example.com",
                               "city": "西安",
                               "jobIntention": [
                                   "前端开发工程师"
                               ]
                           },
                           "education": [
                               {
                                   "school": "法外狂徒大学",
                                   "major": "软件工程",
                                   "degree": "本科",
                                   "startDate": "2022-09",
                                   "endDate": "2026-06",
                                   "gpa": "3.6/4.0"
                               }
                           ],
                           "workExperience": [
                               {
                                   "company": "XX科技有限公司",
                                   "position": "前端开发实习生",
                                   "startDate": "2025-07",
                                   "endDate": "2025-09",
                                   "description": "参与公司后台管理系统的前端开发与维护；使用 Vue3 + TypeScript + Element Plus 技术栈开发用户管理、数据统计等模块；负责页面组件的开发、测试与联调，确保功能稳定运行；优化前端性能，将页面加载速度提升20%；与后端开发人员紧密合作，实现前后端数据交互接口"
                               }
                           ],
                           "skills": [
                               "Vue 3",
                               "Vue Router",
                               "Pinia",
                               "Vite",
                               "TypeScript",
                               "JavaScript (ES6+)",
                               "HTML5",
                               "CSS3",
                               "Element Plus",
                               "Ant Design Vue",
                               "Tailwind CSS",
                               "ECharts",
                               "AntV G2",
                               "Webpack",
                               "Git",
                               "GitHub",
                               "GitLab",
                               "响应式设计",
                               "移动端适配",
                               "RESTful API 设计与调用",
                               "前端工程化",
                               "模块化开发",
                               "Agile/Scrum 开发流程"
                           ],
                           "certificates": [
                               {
                                   "name": "英语六级（CET-6）",
                                   "date": "2024-12",
                                   "issuer": "教育部考试中心"
                               },
                               {
                                   "name": "校级优秀学生干部",
                                   "date": "2024-06",
                                   "issuer": "XX大学"
                               },
                               {
                                   "name": "前端开发工程师认证",
                                   "date": "2024-03",
                                   "issuer": "慕课网"
                               },
                               {
                                   "name": "Vue.js 高级开发者认证",
                                   "date": "2023-10",
                                   "issuer": "开源社区"
                               }
                           ],
                           "organizeExp": [
                               "学生会主席 | XX大学学生会 | 2023年9月 - 2024年9月：负责学生会日常工作的组织与协调；策划并执行校园年度音乐会，吸引观众800余人；协调10个社团参与演出，管理20人志愿者团队；通过活动筹款2万余元用于学生活动基金；代表学生参与学校管理决策会议；培养了优秀的组织协调能力和团队管理能力",
                               "技术社区参与：定期在校园技术社区分享前端开发经验；参与 Element Plus 组件库的文档翻译工作；在个人博客撰写前端技术文章，累计阅读量10万+"
                           ],
                           "projects": [
                               "二手交易平台 | 个人项目 | 2024年3月 - 2024年6月：一个基于Vue 3开发的校园二手交易平台，支持商品发布、搜索、在线聊天等功能；技术栈：Vue 3 + TypeScript + Element Plus + Node.js + MySQL；负责前端架构设计和核心模块开发；实现商品展示、搜索过滤、用户认证等核心功能；集成 WebSocket 实现在线聊天功能；使用 ECharts 实现用户行为数据可视化；平台上线后累计注册用户500+，日活跃用户80+",
                               "后台管理系统 | 实习项目 | 2025年7月 - 2025年9月：XX科技内部使用的后台管理系统，包含用户管理、数据统计、权限控制等模块；技术栈：Vue 3 + TypeScript + Element Plus + ECharts；开发了完整的用户权限管理模块；设计了可复用的数据表格组件，提升开发效率30%；实现了多维度数据统计可视化看板；优化了前端打包配置，减少构建时间40%"
                           ],
                           "selfEvaluation": "学习能力强，善于沟通，有项目落地经验；技术学习：保持对前端技术的热情，持续学习新技术，每周投入15+小时进行技术钻研；沟通协作：具备良好的团队协作精神，能够清晰表达技术观点，积极参与团队讨论；项目落地：从需求分析到上线部署，具备完整的项目开发经验，注重代码质量和用户体验；问题解决：善于分析问题本质，能够独立解决复杂技术难题；责任心强：对工作认真负责，注重细节，追求代码的优雅与高效"
                       }""";

}
