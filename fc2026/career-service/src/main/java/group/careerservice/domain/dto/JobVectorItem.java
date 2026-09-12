package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;

import java.util.List;

/**
 * 岗位探索链路的岗位数据，来源是 pgvector 向量库的 {@code job_detail_vector} 表
 * （写入方是 ai-service-py 的 BOSS 直聘爬虫，见 岗位向量库job_detail_vector.md）。
 *
 * <p>字段只覆盖爬虫真实采集到的内容；ES 版 {@link JobDocument} 里的
 * industryTags / level / companyType / companySize / district / abilityRequirements
 * 在新表里没有对应列，已从契约中去掉。
 *
 * <p>字段来源：
 * <ul>
 *   <li>{@code jobId} ← {@code job_key}（表上 UNIQUE 约束，必然非空，可安全当主键用）</li>
 *   <li>{@code bossJobId} ← {@code metadata->>'jobId'}，形如 {@code boss:8a3f1c...}，只作展示</li>
 *   <li>薪资区间/单位、sourceSite、updatedAtRaw ← {@code metadata->>'xxx'}（列是 json 类型，用 ->> 取文本）</li>
 *   <li>{@code cats} ← {@code cats_json}，分类标签由 keywords.json 的 cat_rules 自动打</li>
 * </ul>
 */
@Data
public class JobVectorItem {

    /** 岗位唯一 id，即 job_key（md5(company+title+city)） */
    private String jobId;

    /** BOSS 招聘帖 id，形如 boss:8a3f1c...，可能为空 */
    private String bossJobId;

    private String jobName;

    private String companyName;

    private String city;

    /** 岗位分类标签，如 ["大模型/LLM", "AI Agent/智能体"] */
    private List<String> cats;

    /** 原始薪资文本，如 20-35K·15薪 */
    private String salaryText;

    /** 薪资区间（解析自 salaryText，解不出为 null） */
    private Double salaryMin;

    private Double salaryMax;

    /** 薪资单位：K / 元/天 / 元/月 */
    private String salaryUnit;

    /** 月平均薪资，单位 K（清洗后数值，薪资区间筛选与排序都基于它） */
    private Double avg;

    /** 薪资档：15-30K / 30-50K / 50K+ ... */
    private String tier;

    /** 经验要求，如 3-5年 */
    private String exp;

    /** 学历要求，如 本科 */
    private String edu;

    /** boss / intern 等 */
    private String source;

    private String sourceSite;

    private String sourceUrl;

    /** 岗位发布日期（爬虫侧的原始日期文本） */
    private String updatedAtRaw;

    /** 最近一次采集到该岗位的时间（重跑刷新） */
    private String lastSeen;

    /** 首次入库时间 */
    private String firstSeen;

    /** 是否本次采集新增 */
    private Boolean isNew;

    /** 是否已向量化、可被 RAG 检索 */
    private Boolean vectorReady;

    /** 岗位 JD，仅详情接口返回，列表接口为 null */
    private String jobDescription;
}
