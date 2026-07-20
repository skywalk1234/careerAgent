package group.profileservice.service;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.dto.ResumeEvaluationResult;
import group.profileservice.domain.po.Evaluation;
import group.profileservice.mapper.EvalMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
@Slf4j
@RequiredArgsConstructor
public class SaveEvalService {
    private final EvalMapper evalMapper;
    public void saveEval(ResumeEvaluationResult result, Long userId){
        Evaluation existingEval = evalMapper.selectOne(
                new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getUserId, userId)
                        .orderByDesc(Evaluation::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (existingEval != null) {
            // 2. 如果存在，则更新原有记录
            log.info("用户 {} 已存在评分记录，ID: {}，执行更新", userId, existingEval.getId());

            existingEval.setScoresData(result);
            existingEval.setUpdatedAt(LocalDateTime.now());
            int rows = evalMapper.updateById(existingEval);

            if (rows > 0) {
                log.info("用户 {} 的评分记录更新成功", userId);
            } else {
                log.error("用户 {} 的评分记录更新失败", userId);
            }
        } else {
            // 3. 如果不存在，则插入新记录
            log.info("用户 {} 不存在评分记录，执行插入", userId);

            Evaluation newEval = new Evaluation();
            newEval.setUserId(userId);
            newEval.setScoresData(result);
            newEval.setCreatedAt(LocalDateTime.now());
            newEval.setUpdatedAt(LocalDateTime.now());

            int rows = evalMapper.insert(newEval);

            if (rows > 0) {
                log.info("用户 {} 的评分记录插入成功，ID: {}", userId, newEval.getId());
            } else {
                log.error("用户 {} 的评分记录插入失败", userId);
            }
        }
    }
    public Evaluation getEval(Integer userId){
        Evaluation existingEval = evalMapper.selectOne(
                new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getUserId, userId)
                        .orderByDesc(Evaluation::getCreatedAt)
                        .last("LIMIT 1")
        );
        if (existingEval != null) {
            log.info("用户 {} 的评分记录获取成功，ID: {}", userId, existingEval.getId());
        } else {
            log.info("用户 {} 的评分记录不存在", userId);
        }
        return existingEval;
    }

    public Integer deleteEval(Long userId){
        return evalMapper.delete(
                new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getUserId, userId)
        );
    }
}
