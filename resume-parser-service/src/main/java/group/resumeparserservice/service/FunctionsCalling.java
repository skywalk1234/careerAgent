package group.resumeparserservice.service;/* I love coding */

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class FunctionsCalling {
    //模拟数据测试用

    public String profileEval(String userId) {
        log.info("调用 profile_eval 专家，userId: {}", userId);

        String mockResult = String.format(
            "用户%s 的画像信息：综合评分 88 分，专业技能优秀，具备良好的学习能力和沟通能力，有前端项目经验",
            userId
        );

        log.info("profile_eval 专家返回结果：{}", mockResult);
        return mockResult;
    }

    public String matchAndRecommend(String filtersJson) {
        log.info("调用 match_and_recommend 专家，filters: {}", filtersJson);

        String mockResult = "根据筛选条件推荐了 15 个匹配岗位，包括前端开发工程师、全栈开发工程师等，平均薪资 15-25k";

        log.info("match_and_recommend 专家返回结果：{}", mockResult);
        return mockResult;
    }
}
