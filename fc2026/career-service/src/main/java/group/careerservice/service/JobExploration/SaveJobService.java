package group.careerservice.service.JobExploration;/* I love coding */


import group.careerservice.domain.dto.JobDocument;
import group.careerservice.domain.vo.JobsFilter;
import group.careerservice.repository.JobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.elasticsearch.index.query.BoolQueryBuilder;
import org.elasticsearch.index.query.MultiMatchQueryBuilder;
import org.elasticsearch.index.query.QueryBuilders;
import org.elasticsearch.search.sort.SortBuilders;
import org.elasticsearch.search.sort.SortOrder;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.elasticsearch.core.ElasticsearchRestTemplate;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.NativeSearchQuery;
import org.springframework.data.elasticsearch.core.query.NativeSearchQueryBuilder;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;


@Service
@RequiredArgsConstructor
@Slf4j
public class SaveJobService {
    private final JobRepository jobRepository;

    private final ElasticsearchRestTemplate elasticsearchRestTemplate;  // 修改为 ElasticsearchRestTemplate
    public void saveJob() {
    }
    public JobDocument queryJobById(String jobId) {
        // 注意：这里要使用实体的 @Id 字段，也就是 jobId
//        这个是在es索引库中查找
        Optional<JobDocument> optional = jobRepository.findById(jobId);

        if (optional.isPresent()) {
            log.info("查询到岗位信息，jobId: {}", jobId);
            return optional.get();
        } else {
            log.warn("未找到对应的岗位信息，jobId: {}", jobId);
            return null;
        }
    }

    public Page<JobDocument> queryJobsByFilter(JobsFilter filter) {
        log.info("开始分页查询岗位信息，filter: {}", filter);

        // 1. 构建查询条件
        BoolQueryBuilder boolQuery = QueryBuilders.boolQuery();

        // 关键词搜索（多字段匹配）
        if (StringUtils.hasText(filter.getKeyword())) {
            MultiMatchQueryBuilder multiMatchQuery = QueryBuilders.multiMatchQuery(filter.getKeyword())
                    .field("jobName", 2.0f)
                    .field("companyName")
                    .field("jobDescription")
                    .field("companyBrief")
                    .field("companyDescription");
            boolQuery.must(multiMatchQuery);
        }

        // 精确匹配筛选条件
        if (StringUtils.hasText(filter.getCity())) {
            boolQuery.filter(QueryBuilders.termQuery("city", filter.getCity()));
        }
        if (StringUtils.hasText(filter.getIndustryTag())) {
            // 精确匹配数组中的某个元素
            boolQuery.filter(QueryBuilders.termQuery("industryTags", filter.getIndustryTag()));
        }
        if (StringUtils.hasText(filter.getEducationRequirement())) {
            String education = filter.getEducationRequirement().trim();

            BoolQueryBuilder educationQuery = QueryBuilders.boolQuery();

            // 条件1：匹配指定学历
            educationQuery.should(QueryBuilders.termQuery("educationRequirement", education));

            // 条件2：学历不限制的情况
            BoolQueryBuilder noLimitQuery = QueryBuilders.boolQuery();

            // 2.1 字段不存在
            noLimitQuery.should(QueryBuilders.boolQuery()
                    .mustNot(QueryBuilders.existsQuery("educationRequirement")));

            // 2.2 字段为空
            noLimitQuery.should(QueryBuilders.boolQuery()
                    .must(QueryBuilders.termQuery("educationRequirement", "")));

            // 2.3 值为"不限"相关
            noLimitQuery.should(QueryBuilders.termQuery("educationRequirement", "不限"));
            noLimitQuery.should(QueryBuilders.termQuery("educationRequirement", "不限制"));
            noLimitQuery.should(QueryBuilders.termQuery("educationRequirement", "无"));
            noLimitQuery.should(QueryBuilders.termQuery("educationRequirement", "无要求"));

            noLimitQuery.minimumShouldMatch(1);
            educationQuery.should(noLimitQuery);
            educationQuery.minimumShouldMatch(1);

            boolQuery.filter(educationQuery);
        }

        if (StringUtils.hasText(filter.getLevel())) {
            boolQuery.filter(QueryBuilders.termQuery("level", filter.getLevel()));
        }
        if (StringUtils.hasText(filter.getCompanySize())) {
            boolQuery.filter(QueryBuilders.termQuery("companySize", filter.getCompanySize()));
        }
        if (StringUtils.hasText(filter.getCompanyType())) {
            boolQuery.filter(QueryBuilders.termQuery("companyType", filter.getCompanyType()));
        }

        // 2. 构建排序
        org.elasticsearch.search.sort.SortBuilder<?> sortBuilder;
        if ("salary".equalsIgnoreCase(filter.getSortBy())) {
            sortBuilder = SortBuilders.fieldSort("salaryMin").order(
                    "asc".equalsIgnoreCase(filter.getSortOrder()) ? SortOrder.ASC : SortOrder.DESC
            );
        } else if ("updatedAt".equalsIgnoreCase(filter.getSortBy())) {
            sortBuilder = SortBuilders.fieldSort("updatedAtNormalized").order(
                    "asc".equalsIgnoreCase(filter.getSortOrder()) ? SortOrder.ASC : SortOrder.DESC
            );
        } else if ("createdAt".equalsIgnoreCase(filter.getSortBy())) {
            sortBuilder = SortBuilders.fieldSort("createdAt").order(
                    "asc".equalsIgnoreCase(filter.getSortOrder()) ? SortOrder.ASC : SortOrder.DESC
            );
        } else {
            sortBuilder = SortBuilders.scoreSort().order(SortOrder.DESC);
        }

        // 3. 构建分页
        int page = filter.getPage() != null && filter.getPage() > 0 ? filter.getPage() - 1 : 0;
        int size = filter.getPageSize() != null && filter.getPageSize() > 0 ? filter.getPageSize() : 20;
        Pageable pageable = PageRequest.of(page, size);

        // 4. 构建 NativeSearchQuery
        NativeSearchQuery query = new NativeSearchQueryBuilder()
                .withQuery(boolQuery)
                .withPageable(pageable)
                .withSort(sortBuilder)
                .build();

        // 5. 执行查询
        SearchHits<JobDocument> searchHits = elasticsearchRestTemplate.search(query, JobDocument.class);

        // 6. 转换为 Page 对象
        List<JobDocument> jobs = searchHits.get().collect(Collectors.toList())
                .stream()
                .map(hit -> hit.getContent())
                .collect(Collectors.toList());

        return new PageImpl<>(jobs, pageable, searchHits.getTotalHits());
    }

}
