package group.profileservice.service;/* I love coding */

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import group.dto.ResumeEvaluationResult;
import group.dto.StudentProfile;
import group.profileservice.domain.po.Evaluation;
import group.profileservice.domain.po.ResumeFull;
import group.profileservice.mapper.ProfileMapper;
import group.profileservice.mapper.ScoreMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class QueryProfileService {
    private final ProfileMapper profileMapper;
    private final ScoreMapper scoreMapper;
    public StudentProfile getProfile(Long userId) {
        QueryWrapper<ResumeFull> wrapper = new QueryWrapper<>();
        wrapper.eq("user_id", userId).orderByDesc("updated_at").last("LIMIT 1");
        ResumeFull resumeFull = profileMapper.selectOne(wrapper);

        if (resumeFull == null) {
            log.info("用户id为{}的学生画像不存在...", userId);
            return null;
        }

        StudentProfile profile = resumeFull.getResumeData();
        log.info("查询用户id为{}的学生画像成功", userId);
        return profile;
    }

    public ResumeEvaluationResult getEvaluationResult(Long userId) {
        QueryWrapper<Evaluation> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("user_id", userId);
        Evaluation eval = scoreMapper.selectOne(queryWrapper);

        if (eval == null) {
            log.warn("用户id为{}的简历评估结果不存在...", userId);
            return null;
        }

        ResumeEvaluationResult evaluationResult = eval.getScoresData();

        if (evaluationResult == null) {
            log.warn("用户id为{}的简历评估结果数据为空", userId);
            return null;
        }

        log.info("查询用户id为{}的简历评估结果成功", userId);
        return evaluationResult;
    }
}
