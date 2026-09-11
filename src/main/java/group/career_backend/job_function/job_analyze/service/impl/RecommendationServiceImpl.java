package group.career_backend.job_function.job_analyze.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import group.career_backend.exception.CommonException;
import group.career_backend.job_function.job_analyze.domain.dto.AiRecommendRequest;
import group.career_backend.job_function.job_analyze.domain.dto.MatchFilter;
import group.career_backend.job_function.job_analyze.domain.dto.MatchJob;
import group.career_backend.job_function.job_analyze.domain.dto.RecommendMessage;
import group.career_backend.job_function.job_analyze.domain.po.RecommendJob;
import group.career_backend.job_function.job_analyze.mapper.RecommendJobMapper;
import group.career_backend.job_function.job_analyze.service.RecommendationService;
import group.career_backend.profile.domain.response.GetProfileResponse;
import group.career_backend.profile.service.ProfileService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Service
@Slf4j
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    public static final String RECOMMEND_QUEUE = "recommend";

    private final RecommendJobMapper recommendJobMapper;
    private final ProfileService profileService;
    private final RabbitTemplate rabbitTemplate;
    private final JobAnalyzeAiClient aiClient;

    @Override
    public RecommendJob getLatest(Long userId, int topN) {
        log.info("[业务处理] 查询最新岗位推荐, userId={}, topN={}", userId, topN);
        RecommendJob record = recommendJobMapper.selectOne(new LambdaQueryWrapper<RecommendJob>()
                .eq(RecommendJob::getUserId, userId)
                .orderByDesc(RecommendJob::getCreatedAt)
                .orderByDesc(RecommendJob::getId)
                .last("LIMIT 1"));
        if (record != null && record.getMatchResult() != null) {
            trimToTopN(record.getMatchResult(), topN);
        }
        log.info("[业务处理] 最新岗位推荐查询完成, userId={}, found={}", userId, record != null);
        return record;
    }

    @Override
    public void requestRecommendation(Long userId, MatchFilter filters) {
        requireProfile(userId);
        log.info("[业务处理] 发送岗位推荐消息, userId={}, topN={}", userId, filters.getTopN());
        rabbitTemplate.convertAndSend(RECOMMEND_QUEUE, new RecommendMessage(userId, filters));
    }

    @Override
    @RabbitListener(queues = RECOMMEND_QUEUE)
    public void consume(RecommendMessage message) {
        if (message == null || message.getUserId() == null || message.getFilters() == null) {
            log.warn("[消息消费] 忽略无效岗位推荐消息");
            return;
        }
        Long userId = message.getUserId();
        log.info("[消息消费] 开始处理岗位推荐消息, userId={}", userId);
        GetProfileResponse profile = requireProfile(userId);
        MatchJob result = aiClient.recommend(new AiRecommendRequest(
                userId, profile.getProfile(), message.getFilters()));
        trimToTopN(result, normalizeTopN(message.getFilters().getTopN()));

        RecommendJob record = new RecommendJob();
        record.setUserId(userId);
        record.setMatchResult(result);
        record.setCreatedAt(LocalDateTime.now());
        if (recommendJobMapper.insert(record) != 1) {
            throw new CommonException("保存岗位推荐结果失败", 500);
        }
        log.info("[消息消费] 岗位推荐结果保存完成, userId={}, recordId={}", userId, record.getId());
    }

    private GetProfileResponse requireProfile(Long userId) {
        GetProfileResponse profile = profileService.getProfileResponse(userId);
        if (profile == null || !profile.isHasProfile() || profile.getProfile() == null) {
            throw new CommonException("请先完成画像分析后再进行岗位匹配", 409);
        }
        return profile;
    }

    private void trimToTopN(MatchJob result, int topN) {
        if (result == null) {
            return;
        }
        List<MatchJob.Recommendation> others = result.getOtherRecommendations();
        if (others == null) {
            result.setOtherRecommendations(new ArrayList<>());
            return;
        }
        int remaining = result.getBestMatch() == null ? topN : Math.max(0, topN - 1);
        if (others.size() > remaining) {
            result.setOtherRecommendations(new ArrayList<>(others.subList(0, remaining)));
        }
    }

    public static int normalizeTopN(Integer topN) {
        return topN == null ? 10 : Math.max(1, topN);
    }
}
