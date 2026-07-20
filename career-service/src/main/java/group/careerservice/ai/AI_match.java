package group.careerservice.ai;/* I love coding */

import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.TestConstant.AI_recommendation;
import group.careerservice.domain.dto.MatchJob;
import group.careerservice.domain.po.MatchJobPo;
import group.careerservice.mapper.MatchMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
@Slf4j
@RequiredArgsConstructor
public class AI_match {
    private final RabbitTemplate rabbitTemplate;
    private final MatchMapper matchMapper;
    //监听传来的岗位信息和用户画像，调用ai进行匹配
    @RabbitListener(queues = "match")
    public void listen_match_prompt(Map<String, Object> message) throws Exception{
        log.info("接收到用户画像和岗位");
        String profile = (String) message.get("profile");
        log.info("用户画像: " + profile);
        String jobs = (String) message.get("jobs");
        log.info("岗位: " + jobs);
        String userId = (String) message.get("userId");
        //调用ai进行匹配推荐
//        返回字段映射到po.MatchJob中
        // 简单直接的映射方法，成功
        ObjectMapper objectMapper = new ObjectMapper();
        String json = AI_recommendation.recommendation;
        MatchJob matchJob = objectMapper.readValue(json, MatchJob.class);

//        保存到数据库

        MatchJobPo po = new MatchJobPo();
        po.setMatchResult(matchJob);
        po.setUserId(Long.parseLong(userId));
        int insert = matchMapper.insert(po);
        if (insert > 0) {
            log.info("岗位推荐保存成功");
        }else {
            log.info("岗位推荐保存失败");
        }


    }
}
