package group.careerservice.service.JobAnalyze;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.client.AiClient;
import group.careerservice.client.JobRecommendClient;
import group.careerservice.client.ProfileClient;
import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.dto.MatchJob;
import group.careerservice.domain.po.MatchJobPo;
import group.vo.MatchFilter;
import group.careerservice.mapper.MatchMapper;
import group.common.Result;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.*;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProcessMatchFilterService {

    @Autowired
    private ElasticsearchRestTemplate elasticsearchRestTemplate;
    @Autowired
    private final AiClient aiClient;
    private final JobRecommendClient jobRecommendClient;
    private final ProfileClient profileClient;
    private final RabbitTemplate rabbitTemplate;
    private final MatchMapper matchJobPoMapper;

//    接受自查询推荐岗位的请求


    public MatchJobPo queryRecommendations(String userId, Integer topN) {
        // 参数校验
        if (userId == null || userId.trim().isEmpty()) {
            throw new IllegalArgumentException("userId不能为空");
        }

        try {
            // 将字符串类型的userId转换为Long类型
            Long userIdLong = Long.parseLong(userId);

            // 构建查询条件
            QueryWrapper<MatchJobPo> queryWrapper = new QueryWrapper<>();
            queryWrapper.eq("userId", userIdLong);

            // 查询数据库，获取匹配任务实体列表
            List<MatchJobPo> matchJobPos = matchJobPoMapper.selectList(queryWrapper);

            if (matchJobPos == null || matchJobPos.isEmpty()) {
                // 没有查询到数据时，返回null或空对象，根据业务需求调整
                log.info("没查询到有关id:{}的推荐结果", userId);
                return null;
            }

            // 假设每个userId在数据库中只有一条记录，取第一条
            // 如果有多个记录，需要根据业务逻辑决定如何处理
            MatchJobPo matchJobPo = matchJobPos.get(0);
            log.info("查询到有关id:{}的推荐结果: {}", userId, matchJobPo);
            // 从实体中获取MatchResult字段（即MatchJob对象）
            return matchJobPo;

        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("userId格式不正确，应为有效的数字字符串", e);
        }
    }



//    接收自根据筛选条件进行岗位推荐的请求
    @RabbitListener(queues = "recommend")
    public void readyToAI(Map<String, Object> msg) throws Exception{
        String userId = (String) msg.get("userId");
        MatchFilter filter = (MatchFilter) msg.get("filter");

//        List<JobDocument> jobs = searchJobs(filter);

//        //先尝试不筛选岗位
//        log.info("拿到岗位列表: " + jobs.size());
        //向profile-service请求拿到用户画像和用户简历
        Result profile = profileClient.getProfile(userId);
//        controller已经验证过画像是否存在了，所以这里一定成功
        log.info("拿到学生画像: " + profile.getCode());
        Object data = profile.getData();//把这个东西转成json字符串，把jobs也转成字符串，全部扔给ai
        ObjectMapper objectMapper = new ObjectMapper();
        String profile_json = objectMapper.writeValueAsString(data);


//        String jobs_json = objectMapper.writeValueAsString(jobs);
        //传入消息队列异步执行, 在AI_recommend中处理
        Map<String, Object> profile_msg = new HashMap<>();
//
        profile_msg.put("userId", userId);
//        profile_msg.put("jobs", "");
        profile_msg.put("求职意愿", filter);
        profile_msg.put("profile", profile_json);
        String request_json = objectMapper.writeValueAsString(profile_msg);
//       向Python ai-service (ai-service-py) 发送请求（取代原 resume-parser-service 的 AI_recommend 步骤）
        String s = jobRecommendClient.recommendJobs(request_json);
        System.out.println("接收到大类的推荐结果："+ s);
        profile_msg.put("大模型推荐的岗位", s);

        String recommendations = jobRecommendClient.recommendSpecificJobs(request_json);
        log.info("接收到具体岗位的推荐结果："+ recommendations);

        MatchJob matchJob = objectMapper.readValue(recommendations, MatchJob.class);
        MatchJobPo matchJobPo = new MatchJobPo();
        matchJobPo.setMatchResult(matchJob);
        matchJobPo.setUserId(Long.parseLong(userId));
        int insert = matchJobPoMapper.insert(matchJobPo);
        if (insert > 0) {
            log.info("插入匹配推荐结果成功");
        }

    }


    /**
     * 根据过滤条件查询岗位
     * @param filter 过滤条件
     * @return 符合条件的岗位列表
     */
    public List<JobDocument> searchJobs(MatchFilter filter) {
        if (filter == null || filter.getScope() == null) {
            return new ArrayList<>();
        }

        MatchFilter.Scope scope = filter.getScope();

        // 构建布尔查询
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        // 1. 处理首选岗位ID过滤
        if (scope.getPreferredJobIds() != null && !scope.getPreferredJobIds().isEmpty()) {
            TermsQueryBuilder jobIdQuery = QueryBuilders.termsQuery("jobId", scope.getPreferredJobIds());
            if (Boolean.TRUE.equals(scope.getIncludeSimilarJobs())) {
                boolQuery.should(jobIdQuery);
            } else {
                boolQuery.must(jobIdQuery);
            }
        }

        // 2. 处理首选岗位关键词过滤
        if (scope.getPreferredJobKeywords() != null && !scope.getPreferredJobKeywords().isEmpty()) {
            BoolQueryBuilder keywordQuery = QueryBuilders.boolQuery();
            for (String keyword : scope.getPreferredJobKeywords()) {
                keywordQuery.should(QueryBuilders.matchQuery("jobName", keyword));
                keywordQuery.should(QueryBuilders.matchQuery("jobDescription", keyword));
            }
            if (Boolean.TRUE.equals(scope.getIncludeSimilarJobs())) {
                boolQuery.should(keywordQuery);
            } else {
                boolQuery.must(keywordQuery);
            }
        }

        // 3. 处理城市意向过滤
        if (scope.getCityIntents() != null && !scope.getCityIntents().isEmpty()) {
            TermsQueryBuilder cityQuery = QueryBuilders.termsQuery("city", scope.getCityIntents());
            boolQuery.must(cityQuery);
        }

        // 4. 处理福利待遇过滤
        if (scope.getBenefits() != null && !scope.getBenefits().isEmpty()) {
            BoolQueryBuilder benefitsQuery = QueryBuilders.boolQuery();
            for (String benefit : scope.getBenefits()) {
                benefitsQuery.must(QueryBuilders.matchQuery("jobDescription", benefit));
            }
            boolQuery.must(benefitsQuery);
        }

        // 5. 处理薪资范围过滤

        if (scope.getSalaryRange() != null && !scope.getSalaryRange().isEmpty()) {
            Integer min = scope.getSalaryRange().get("min");
            Integer max = scope.getSalaryRange().get("max");

            if (min != null || max != null) {
                // 查询薪资在范围内的职位
                BoolQueryBuilder salaryQuery = QueryBuilders.boolQuery();

                // 条件1：职位最低薪资 <= 用户最高期望薪资（当用户有max时）
                if (max != null) {
                    // salaryMin <= max
                    salaryQuery.must(QueryBuilders.rangeQuery("salaryMin").lte(max));
                }

                // 条件2：职位最高薪资 >= 用户最低期望薪资（当用户有min时）
                if (min != null) {
                    // salaryMax >= min
                    salaryQuery.must(QueryBuilders.rangeQuery("salaryMax").gte(min));
                }

                // 处理可面议的情况
                BoolQueryBuilder salaryFinalQuery = QueryBuilders.boolQuery();
                salaryFinalQuery.should(salaryQuery);
                salaryFinalQuery.should(QueryBuilders.termQuery("salaryNegotiable", true));

                boolQuery.must(salaryFinalQuery);
            }
        }

        // 6. 处理相似岗位逻辑
        if (Boolean.TRUE.equals(scope.getIncludeSimilarJobs())) {
            // 如果开启了相似岗位，降低其他条件的权重
            boolQuery.minimumShouldMatch(1);
        }

        // 构建查询
        NativeSearchQuery searchQuery = new NativeSearchQueryBuilder()
                .withQuery(boolQuery)
                .withPageable(PageRequest.of(0, filter.getTopN() != null ? filter.getTopN() : 10))
                .withSort(SortBuilders.fieldSort("updatedAtNormalized").order(SortOrder.DESC))
                .build();

        // 执行查询
        SearchHits<JobDocument> searchHits = elasticsearchRestTemplate.search(searchQuery, JobDocument.class);

        // 转换为列表
        return searchHits.getSearchHits()
                .stream()
                .map(SearchHit::getContent)
                .collect(Collectors.toList());
    }
}