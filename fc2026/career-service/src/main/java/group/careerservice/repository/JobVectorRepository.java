package group.careerservice.repository;/* I love coding */

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import group.careerservice.domain.dto.JobVectorItem;
import group.careerservice.domain.vo.JobVectorFilter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 岗位探索的 pgvector 读端：查 {@code job_detail_vector}（写入方是 ai-service-py 的爬虫）。
 *
 * <p>整套 SQL 都基于这里固定的列名 + 白名单排序，不存在把用户输入拼进 SQL 的路径。
 *
 * <p>注意 {@code metadata} 是 json 类型（不是 jsonb），用 {@code ->>} 取文本即可，
 * 不直接 SELECT 整列，免得拿到 PGobject 还要额外处理。
 */
@Repository
@Slf4j
public class JobVectorRepository {

    private static final String TABLE = "job_detail_vector";

    /** 排序字段白名单：sortBy → 实际列名。其余一律回落到 last_seen，杜绝注入 */
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "salary", "avg",
            "updatedAt", "last_seen",
            "createdAt", "first_seen"
    );

    /** 列表查询的列（不含 content，JD 正文比较长，列表用不到） */
    private static final String LIST_COLUMNS = """
            job_key, title, company, city, salary, avg, tier, exp, edu,
            cats_json::text AS cats_json, url, source, first_seen, last_seen, is_new, vector_ready,
            metadata ->> 'jobId'       AS boss_job_id,
            metadata ->> 'salaryMin'   AS salary_min,
            metadata ->> 'salaryMax'   AS salary_max,
            metadata ->> 'salaryUnit'  AS salary_unit,
            metadata ->> 'sourceSite'  AS source_site,
            metadata ->> 'updatedAtRaw' AS updated_at_raw
            """;

    private final JdbcTemplate vectorJdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JobVectorRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
    }

    /** 分页查询结果：total 是满足条件的总条数，list 是当前页 */
    public record PageResult(long total, List<JobVectorItem> list) {
    }

    public PageResult queryPage(JobVectorFilter filter) {
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendConditions(where, args, filter);

        Long total = vectorJdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + where, Long.class, args.toArray());
        if (total == null || total == 0) {
            return new PageResult(0, List.of());
        }

        int page = filter.getPage() != null && filter.getPage() > 0 ? filter.getPage() : 1;
        int size = filter.getPageSize() != null && filter.getPageSize() > 0 ? filter.getPageSize() : 20;

        String column = SORT_COLUMNS.getOrDefault(
                StringUtils.hasText(filter.getSortBy()) ? filter.getSortBy().trim() : "", "last_seen");
        String direction = "asc".equalsIgnoreCase(StringUtils.hasText(filter.getSortOrder())
                ? filter.getSortOrder().trim() : "") ? "ASC" : "DESC";

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(size);
        pageArgs.add((page - 1) * size);

        // id DESC 兜底，保证同一排序值下分页稳定（不会出现翻页丢/重行）
        String sql = "SELECT " + LIST_COLUMNS + " FROM " + TABLE + where
                + " ORDER BY " + column + " " + direction + " NULLS LAST, id DESC"
                + " LIMIT ? OFFSET ?";

        List<JobVectorItem> list = vectorJdbcTemplate.query(sql, LIST_ROW_MAPPER, pageArgs.toArray());
        return new PageResult(total, list);
    }

    /** 按 job_key 查单条详情（多带 content 作为 jobDescription）；查不到返回 null */
    public JobVectorItem findByJobKey(String jobKey) {
        if (!StringUtils.hasText(jobKey)) {
            return null;
        }
        String sql = "SELECT " + LIST_COLUMNS + ", content FROM " + TABLE + " WHERE job_key = ?";
        List<JobVectorItem> rows = vectorJdbcTemplate.query(sql, LIST_ROW_MAPPER, jobKey.trim());
        return rows.isEmpty() ? null : rows.get(0);
    }

    /**
     * 筛选下拉的候选值：各列 DISTINCT 非空值，按值排序。
     * 返回 {city:[...], edu:[...], exp:[...], tier:[...]}，避免为 4 个下拉写 4 个方法。
     */
    public Map<String, List<String>> loadFilterOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("city", distinctColumn("city"));
        options.put("edu", distinctColumn("edu"));
        options.put("exp", distinctColumn("exp"));
        options.put("tier", distinctColumn("tier"));
        return options;
    }

    /**
     * 取某列的 DISTINCT 非空值。
     * 不做白名单过滤——这些取值全部来自我们自己的库，且回填到 IN 里也是绑定参数，
     * 硬编码取值反而会在爬虫调整薪资档时让下拉静默变空。
     */
    private List<String> distinctColumn(String column) {
        String sql = "SELECT DISTINCT " + column + " AS v FROM " + TABLE
                + " WHERE " + column + " IS NOT NULL AND " + column + " <> '' ORDER BY v";
        return vectorJdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("v"));
    }

    // ==================== WHERE 组装 ====================

    private void appendConditions(StringBuilder where, List<Object> args, JobVectorFilter filter) {
        if (StringUtils.hasText(filter.getKeyword())) {
            // ILIKE 默认以反斜杠为转义符，先转义用户输入里的 % _ \ 再做包含匹配
            String like = "%" + escapeLike(filter.getKeyword().trim()) + "%";
            where.append(" AND (title ILIKE ? OR company ILIKE ? OR content ILIKE ?)");
            Collections.addAll(args, like, like, like);
        }
        if (StringUtils.hasText(filter.getCity())) {
            where.append(" AND city = ?");
            args.add(filter.getCity().trim());
        }
        if (filter.getSalaryMin() != null) {
            where.append(" AND avg >= ?");
            args.add(filter.getSalaryMin());
        }
        if (filter.getSalaryMax() != null) {
            where.append(" AND avg <= ?");
            args.add(filter.getSalaryMax());
        }
        appendInClause(where, args, "tier", filter.getSalaryTier());
        appendInClause(where, args, "exp", filter.getExp());
        appendInClause(where, args, "edu", filter.getEdu());
    }

    private void appendInClause(StringBuilder where, List<Object> args, String column, List<String> values) {
        List<String> cleaned = new ArrayList<>();
        for (String value : values == null ? Collections.<String>emptyList() : values) {
            if (StringUtils.hasText(value)) {
                cleaned.add(value.trim());
            }
        }
        if (cleaned.isEmpty()) {
            return;
        }
        where.append(" AND ").append(column).append(" IN (")
                .append(String.join(", ", Collections.nCopies(cleaned.size(), "?"))).append(")");
        args.addAll(cleaned);
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    // ==================== 行映射 ====================

    private final RowMapper<JobVectorItem> LIST_ROW_MAPPER = (rs, rowNum) -> {
        JobVectorItem item = new JobVectorItem();
        item.setJobId(rs.getString("job_key"));
        item.setBossJobId(rs.getString("boss_job_id"));
        item.setJobName(rs.getString("title"));
        item.setCompanyName(rs.getString("company"));
        item.setCity(rs.getString("city"));
        item.setCats(parseCats(rs.getString("cats_json")));
        item.setSalaryText(rs.getString("salary"));
        item.setSalaryMin(toDouble(rs.getString("salary_min")));
        item.setSalaryMax(toDouble(rs.getString("salary_max")));
        item.setSalaryUnit(rs.getString("salary_unit"));
        item.setAvg(toDouble(rs.getObject("avg")));
        item.setTier(rs.getString("tier"));
        item.setExp(rs.getString("exp"));
        item.setEdu(rs.getString("edu"));
        item.setSource(rs.getString("source"));
        item.setSourceSite(rs.getString("source_site"));
        item.setSourceUrl(rs.getString("url"));
        item.setUpdatedAtRaw(rs.getString("updated_at_raw"));
        item.setLastSeen(toIsoText(rs, "last_seen"));
        item.setFirstSeen(toIsoText(rs, "first_seen"));
        item.setIsNew(rs.getObject("is_new") != null && rs.getInt("is_new") != 0);
        item.setVectorReady(rs.getObject("vector_ready") == null ? Boolean.FALSE : rs.getBoolean("vector_ready"));
        item.setJobDescription(readOptionalString(rs, "content"));
        return item;
    };

    private List<String> parseCats(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (Exception e) {
            log.warn("解析 cats_json 失败，按空列表处理：{}", json, e);
            return List.of();
        }
    }

    private Double toDouble(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.valueOf(String.valueOf(value).trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String readOptionalString(ResultSet rs, String column) throws SQLException {
        // 列表查询的 SELECT 里没有 content 列，取列前先确认存在
        try {
            return rs.getString(column);
        } catch (SQLException e) {
            return null;
        }
    }

    /** timestamptz → ISO-8601 带时区文本（前端 new Date(...) 可直接解析） */
    private String toIsoText(ResultSet rs, String column) throws SQLException {
        OffsetDateTime dateTime = rs.getObject(column, OffsetDateTime.class);
        if (dateTime != null) {
            return dateTime.toString();
        }
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().toString();
    }
}
