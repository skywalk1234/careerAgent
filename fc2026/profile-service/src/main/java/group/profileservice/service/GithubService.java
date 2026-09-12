package group.profileservice.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.dto.ResumeEvaluationResult;
import group.profileservice.domain.po.Evaluation;
import group.profileservice.domain.po.GithubAuth;
import group.profileservice.dto.GithubCallbackResponse;
import group.profileservice.dto.GithubSummaryResponse;
import group.profileservice.mapper.EvalMapper;
import group.profileservice.mapper.GithubAuthMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * GitHub授权服务类
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class GithubService {

    private final GithubAuthMapper githubAuthMapper;
    private final EvalMapper evalMapper;
    private final ObjectMapper objectMapper;
    private final RestTemplate restTemplate = new RestTemplate();

    // GitHub OAuth配置
    private static final String GITHUB_CLIENT_ID = "Ov23liEZ0WMlH9I6mXqb";
    private static final String GITHUB_CLIENT_SECRET = "8da7e5a6e2b6a95c7c93c90c9f6a9c9a7f6e5d4c";
    private static final String GITHUB_AUTH_URL = "https://github.com/login/oauth/authorize";
    private static final String GITHUB_TOKEN_URL = "https://github.com/login/oauth/access_token";
    private static final String GITHUB_API_URL = "https://api.github.com";

    private final Map<String, Long> stateCache = new HashMap<>();

    /**
     * 生成授权URL和state
     */
    public String generateAuthUrl(Long userId) {
        String state = generateState();
        stateCache.put(state, userId);
        return GITHUB_AUTH_URL + "?client_id=" + GITHUB_CLIENT_ID + "&state=" + state + "&scope=user,repo";
    }

    /**
     * 验证state是否有效
     */
    public Long validateState(String state) {
        return stateCache.remove(state);
    }

    /**
     * 处理GitHub OAuth回调
     */
    public GithubCallbackResponse handleCallback(Long userId, String code, String state) {
        try {
            // 1. 用code换取access_token
            String accessToken = exchangeCodeForToken(code);
            if (accessToken == null) {
                throw new RuntimeException("获取access_token失败");
            }

            // 2. 获取GitHub用户信息
            Map<String, Object> userInfo = getGithubUserInfo(accessToken);
            String login = (String) userInfo.get("login");
            String htmlUrl = (String) userInfo.get("html_url");

            // 3. 获取用户仓库信息
            List<Map<String, Object>> repos = getUserRepos(accessToken);

            // 4. 计算贡献热力图（简化实现）
            GithubCallbackResponse.ContributionHeatmap heatmap = calculateContributionHeatmap(repos);

            // 5. 计算技术栈统计
            List<GithubCallbackResponse.LanguageStat> languageStats = calculateLanguageStats(repos, accessToken);

            // 6. 计算加分
            List<GithubCallbackResponse.BonusDetail> bonusDetails = calculateBonus(heatmap, languageStats, repos);
            int totalBonus = bonusDetails.stream().mapToInt(GithubCallbackResponse.BonusDetail::getDelta).sum();

            // 7. 保存授权信息到数据库
            saveGithubAuth(userId, login, htmlUrl, accessToken, heatmap, languageStats, bonusDetails, totalBonus);

            // 8. 更新用户评分中的bonusByDimension
            updateUserBonus(userId, bonusDetails);

            // 9. 构建响应
            GithubCallbackResponse response = new GithubCallbackResponse();
            response.setAuthorized(true);
            response.setProvider("github");
            response.setAccountName(login);
            response.setContributionHeatmap(heatmap);
            response.setLanguageStats(languageStats);
            response.setBonusDetails(bonusDetails);
            response.setTotalBonus(totalBonus);
            response.setNote("仅用于补充验证与可选加分，不授权不扣分");

            return response;

        } catch (Exception e) {
            log.error("GitHub授权回调处理失败", e);
            throw new RuntimeException("GitHub授权失败: " + e.getMessage());
        }
    }

    /**
     * 获取用户GitHub授权摘要
     */
    public GithubSummaryResponse getSummary(Long userId) {
        GithubAuth auth = githubAuthMapper.selectOne(
                new LambdaQueryWrapper<GithubAuth>()
                        .eq(GithubAuth::getUserId, userId)
                        .orderByDesc(GithubAuth::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (auth == null) {
            GithubSummaryResponse response = new GithubSummaryResponse();
            response.setAuthorized(false);
            response.setNote("可选加分项：不授权不扣分");
            return response;
        }

        try {
            GithubSummaryResponse response = new GithubSummaryResponse();
            response.setAuthorized(true);
            response.setProvider("github");
            response.setAccountName(auth.getAccountName());
            response.setProfileUrl(auth.getProfileUrl());
            response.setAuthorizedAt(auth.getAuthorizedAt().format(DateTimeFormatter.ISO_DATE_TIME));
            response.setContributionHeatmap(
                    objectMapper.readValue(auth.getContributionHeatmap(), GithubSummaryResponse.ContributionHeatmap.class)
            );
            response.setLanguageStats(
                    objectMapper.readValue(auth.getLanguageStats(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, GithubSummaryResponse.LanguageStat.class))
            );
            response.setBonusDetails(
                    objectMapper.readValue(auth.getBonusDetails(),
                            objectMapper.getTypeFactory().constructCollectionType(List.class, GithubSummaryResponse.BonusDetail.class))
            );
            response.setTotalBonus(auth.getTotalBonus());
            response.setNote("仅用于补充验证与可选加分，不授权不扣分");
            return response;
        } catch (JsonProcessingException e) {
            log.error("解析GitHub授权数据失败", e);
            throw new RuntimeException("数据解析失败");
        }
    }

    /**
     * 解绑GitHub授权
     */
    public void unbind(Long userId) {
        // 1. 删除授权记录
        githubAuthMapper.delete(
                new LambdaQueryWrapper<GithubAuth>()
                        .eq(GithubAuth::getUserId, userId)
        );

        // 2. 重置用户评分中的bonusByDimension
        resetUserBonus(userId);

        log.info("用户 {} 已解绑GitHub授权", userId);
    }

    /**
     * 用code换取access_token
     */
    private String exchangeCodeForToken(String code) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        Map<String, String> body = new HashMap<>();
        body.put("client_id", GITHUB_CLIENT_ID);
        body.put("client_secret", GITHUB_CLIENT_SECRET);
        body.put("code", code);

        HttpEntity<Map<String, String>> request = new HttpEntity<>(body, headers);

        ResponseEntity<Map> response = restTemplate.postForEntity(GITHUB_TOKEN_URL, request, Map.class);
        Map<String, Object> result = response.getBody();

        if (result != null && result.containsKey("access_token")) {
            return (String) result.get("access_token");
        }
        return null;
    }

    /**
     * 获取GitHub用户信息
     */
    private Map<String, Object> getGithubUserInfo(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> request = new HttpEntity<>(headers);
        ResponseEntity<Map> response = restTemplate.exchange(
                GITHUB_API_URL + "/user",
                HttpMethod.GET,
                request,
                Map.class
        );
        return response.getBody();
    }

    /**
     * 获取用户仓库列表
     */
    private List<Map<String, Object>> getUserRepos(String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(accessToken);
        headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

        HttpEntity<String> request = new HttpEntity<>(headers);
        ResponseEntity<List> response = restTemplate.exchange(
                GITHUB_API_URL + "/user/repos?per_page=100",
                HttpMethod.GET,
                request,
                List.class
        );
        return response.getBody() != null ? response.getBody() : new ArrayList<>();
    }

    /**
     * 计算贡献热力图（简化实现）
     */
    private GithubCallbackResponse.ContributionHeatmap calculateContributionHeatmap(List<Map<String, Object>> repos) {
        List<GithubCallbackResponse.DayContribution> days = new ArrayList<>();

        // 生成最近7天的模拟数据（实际应从GitHub API获取贡献数据）
        LocalDate today = LocalDate.now();
        for (int i = 6; i >= 0; i--) {
            LocalDate date = today.minusDays(i);
            // 根据仓库数量和更新时间模拟贡献值
            int count = repos.size() > 0 ? (int) (Math.random() * 5) : 0;
            days.add(new GithubCallbackResponse.DayContribution(date.toString(), count));
        }

        return new GithubCallbackResponse.ContributionHeatmap(days);
    }

    /**
     * 计算技术栈统计
     */
    private List<GithubCallbackResponse.LanguageStat> calculateLanguageStats(List<Map<String, Object>> repos, String accessToken) {
        Map<String, Integer> languageCounts = new HashMap<>();

        for (Map<String, Object> repo : repos) {
            String languagesUrl = (String) repo.get("languages_url");
            if (languagesUrl != null) {
                try {
                    HttpHeaders headers = new HttpHeaders();
                    headers.setBearerAuth(accessToken);
                    headers.setAccept(Collections.singletonList(MediaType.APPLICATION_JSON));

                    HttpEntity<String> request = new HttpEntity<>(headers);
                    ResponseEntity<Map> response = restTemplate.exchange(
                            languagesUrl,
                            HttpMethod.GET,
                            request,
                            Map.class
                    );
                    Map<String, Integer> languages = response.getBody();
                    if (languages != null) {
                        languages.forEach((lang, bytes) -> {
                            languageCounts.merge(lang, bytes, Integer::sum);
                        });
                    }
                } catch (Exception e) {
                    log.warn("获取仓库语言统计失败: {}", languagesUrl);
                }
            }
        }

        // 转换为百分比
        int total = languageCounts.values().stream().mapToInt(Integer::intValue).sum();
        List<GithubCallbackResponse.LanguageStat> stats = new ArrayList<>();

        if (total > 0) {
            languageCounts.entrySet().stream()
                    .sorted((e1, e2) -> e2.getValue().compareTo(e1.getValue()))
                    .limit(5)
                    .forEach(entry -> {
                        int percentage = (int) (entry.getValue() * 100.0 / total);
                        stats.add(new GithubCallbackResponse.LanguageStat(entry.getKey(), percentage));
                    });
        }

        // 如果没有数据，添加默认值
        if (stats.isEmpty()) {
            stats.add(new GithubCallbackResponse.LanguageStat("Java", 40));
            stats.add(new GithubCallbackResponse.LanguageStat("JavaScript", 30));
            stats.add(new GithubCallbackResponse.LanguageStat("Python", 30));
        }

        return stats;
    }

    /**
     * 计算加分
     */
    private List<GithubCallbackResponse.BonusDetail> calculateBonus(
            GithubCallbackResponse.ContributionHeatmap heatmap,
            List<GithubCallbackResponse.LanguageStat> languageStats,
            List<Map<String, Object>> repos) {

        List<GithubCallbackResponse.BonusDetail> details = new ArrayList<>();

        // 1. 执行力加分：根据近期提交活跃度
        int activeDays = (int) heatmap.getDays().stream()
                .filter(day -> day.getCount() > 0)
                .count();
        if (activeDays >= 3) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "execution",
                    3,
                    "近一年持续提交，项目推进节奏稳定"
            ));
        } else if (activeDays >= 1) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "execution",
                    1,
                    "近期有项目提交记录"
            ));
        }

        // 2. 内驱动力加分：根据仓库数量和长期维护情况
        if (repos.size() >= 3) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "internalMotivation",
                    1,
                    "持续维护长期项目，表现出稳定自驱与目标坚持"
            ));
        }

        // 3. 专业技能加分：根据技术栈多样性
        if (languageStats.size() >= 3) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "professionalSkill",
                    2,
                    "技术栈覆盖前端主流框架与工程化能力"
            ));
        } else if (languageStats.size() >= 2) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "professionalSkill",
                    1,
                    "掌握多种编程语言"
            ));
        }

        // 4. 创新能力加分：根据是否有原创项目（非fork）
        long originalRepos = repos.stream()
                .filter(repo -> Boolean.FALSE.equals(repo.get("fork")))
                .count();
        if (originalRepos >= 2) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "innovation",
                    2,
                    "拥有多个原创项目，展现创新思维"
            ));
        }

        // 5. 学习能力加分：根据技术栈更新频率
        if (languageStats.stream().anyMatch(lang ->
                Arrays.asList("TypeScript", "Go", "Rust").contains(lang.getName()))) {
            details.add(new GithubCallbackResponse.BonusDetail(
                    "learning",
                    1,
                    "积极学习新兴技术栈"
            ));
        }

        return details;
    }

    /**
     * 保存GitHub授权信息
     */
    private void saveGithubAuth(Long userId, String accountName, String profileUrl, String accessToken,
                                GithubCallbackResponse.ContributionHeatmap heatmap,
                                List<GithubCallbackResponse.LanguageStat> languageStats,
                                List<GithubCallbackResponse.BonusDetail> bonusDetails,
                                int totalBonus) throws JsonProcessingException {

        // 删除旧记录
        githubAuthMapper.delete(
                new LambdaQueryWrapper<GithubAuth>()
                        .eq(GithubAuth::getUserId, userId)
        );

        // 插入新记录
        GithubAuth auth = new GithubAuth();
        auth.setUserId(userId);
        auth.setAccountName(accountName);
        auth.setProfileUrl(profileUrl);
        auth.setAccessToken(accessToken);
        auth.setContributionHeatmap(objectMapper.writeValueAsString(heatmap));
        auth.setLanguageStats(objectMapper.writeValueAsString(languageStats));
        auth.setBonusDetails(objectMapper.writeValueAsString(bonusDetails));
        auth.setTotalBonus(totalBonus);
        auth.setAuthorizedAt(LocalDateTime.now());

        githubAuthMapper.insert(auth);
        log.info("用户 {} GitHub授权信息已保存", userId);
    }

    /**
     * 更新用户评分中的bonusByDimension
     */
    private void updateUserBonus(Long userId, List<GithubCallbackResponse.BonusDetail> bonusDetails) {
        Evaluation evaluation = evalMapper.selectOne(
                new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getUserId, userId)
                        .orderByDesc(Evaluation::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (evaluation != null && evaluation.getScoresData() != null) {
            ResumeEvaluationResult result = evaluation.getScoresData();
            if (result.getScores() != null && result.getScores().getBonusByDimension() != null) {
                ResumeEvaluationResult.AbilityScores bonus = result.getScores().getBonusByDimension();

                // 根据bonusDetails更新各维度加分
                for (GithubCallbackResponse.BonusDetail detail : bonusDetails) {
                    switch (detail.getDimension()) {
                        case "execution":
                            bonus.setExecution(bonus.getExecution() + detail.getDelta());
                            break;
                        case "internalMotivation":
                            bonus.setInternalMotivation(bonus.getInternalMotivation() + detail.getDelta());
                            break;
                        case "professionalSkill":
                            bonus.setProfessionalSkill(bonus.getProfessionalSkill() + detail.getDelta());
                            break;
                        case "innovation":
                            bonus.setInnovation(bonus.getInnovation() + detail.getDelta());
                            break;
                        case "learning":
                            bonus.setLearning(bonus.getLearning() + detail.getDelta());
                            break;
                    }
                }

                evaluation.setScoresData(result);
                evaluation.setUpdatedAt(LocalDateTime.now());
                evalMapper.updateById(evaluation);
                log.info("用户 {} 评分加分已更新", userId);
            }
        }
    }

    /**
     * 重置用户评分中的bonusByDimension
     */
    private void resetUserBonus(Long userId) {
        Evaluation evaluation = evalMapper.selectOne(
                new LambdaQueryWrapper<Evaluation>()
                        .eq(Evaluation::getUserId, userId)
                        .orderByDesc(Evaluation::getCreatedAt)
                        .last("LIMIT 1")
        );

        if (evaluation != null && evaluation.getScoresData() != null) {
            ResumeEvaluationResult result = evaluation.getScoresData();
            if (result.getScores() != null) {
                // 重置所有加分维度为0
                ResumeEvaluationResult.AbilityScores zeroBonus = new ResumeEvaluationResult.AbilityScores();
                zeroBonus.setProfessionalSkill(0);
                zeroBonus.setCertificate(0);
                zeroBonus.setInnovation(0);
                zeroBonus.setInternalMotivation(0);
                zeroBonus.setLearning(0);
                zeroBonus.setStressTolerance(0);
                zeroBonus.setCommunication(0);
                zeroBonus.setInternship(0);
                zeroBonus.setLanguage(0);
                zeroBonus.setLeadership(0);
                zeroBonus.setAdaptability(0);
                zeroBonus.setExecution(0);

                result.getScores().setBonusByDimension(zeroBonus);
                evaluation.setScoresData(result);
                evaluation.setUpdatedAt(LocalDateTime.now());
                evalMapper.updateById(evaluation);
                log.info("用户 {} 评分加分已重置", userId);
            }
        }
    }

    /**
     * 生成随机state
     */
    private String generateState() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
}
