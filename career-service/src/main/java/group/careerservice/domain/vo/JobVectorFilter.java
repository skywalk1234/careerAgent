package group.careerservice.domain.vo;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 岗位探索（pgvector 版）的筛选请求参数。
 *
 * <p>与 ES 版 {@link JobsFilter} 的区别：ES 的 industryTag / level / companySize / companyType
 * 在 job_detail_vector 里没有对应列，已去掉；换成本表真实有的
 * <b>薪资区间（avg 数值）+ 薪资档（tier）</b>、<b>经验（exp）+ 学历（edu）</b>。
 * {@code JobsFilter} 保留给 AdminController 的 ES 分支继续使用。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JobVectorFilter {

    /** 搜索关键词：岗位名 / 公司名 / JD 正文 模糊匹配 */
    private String keyword;

    private String city;

    /** 月平均薪资下限（K），比较 avg 字段 */
    private Double salaryMin;

    /** 月平均薪资上限（K），比较 avg 字段 */
    private Double salaryMax;

    /** 薪资档，如 15-30K，可多选 */
    private List<String> salaryTier;

    /** 经验要求，如 3-5年，可多选 */
    private List<String> exp;

    /** 学历要求，如 本科，可多选 */
    private List<String> edu;

    /** salary / updatedAt / createdAt（映射到 avg / last_seen / first_seen） */
    @Builder.Default
    private String sortBy = "salary";

    /** asc / desc */
    @Builder.Default
    private String sortOrder = "desc";

    @Builder.Default
    private Integer page = 1;

    @Builder.Default
    private Integer pageSize = 20;
}
