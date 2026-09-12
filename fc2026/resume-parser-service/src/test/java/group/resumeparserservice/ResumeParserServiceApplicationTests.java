package group.resumeparserservice;

import group.dto.StudentProfile;
import group.resumeparserservice.service.AI_score;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.HashMap;
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
        //模拟markdown整理完成
        stu_profile.setId(10L);
        stu_profile.setContent("## 张三\n\n**电话**：13800138000\n\n### 教育背景\n- 上海交通大学 | 软件工程 | 本科 | 2010-09 至 2014-06\n\n### 技能\n- Java\n- C++\n- Python");
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
            ## 张三

            **基本信息**
            - 电话：13800138000
            - 邮箱：zhangsan@example.com
            - 求职意向：前端开发工程师

            ### 教育背景
            - 法外狂徒大学 | 软件工程 | 本科 | 2022-09 至 2026-06

            ### 工作经历
            - XX科技有限公司 | 前端开发实习生 | 2025-07 至 2025-09
              - 负责页面组件的开发、测试与联调，使用 Vue3 + TypeScript + Element Plus
              - 优化前端性能，将页面加载速度提升20%

            ### 项目经历
            - 二手交易平台：基于 Vue 3 开发，负责前端架构设计与核心模块开发，上线后注册用户500+
            """;

}
