package group.career_backend.job_function.job_explore.repository;

import group.career_backend.job_function.job_explore.domain.dto.JobVectorItem;
import group.career_backend.job_function.job_explore.domain.vo.JobVectorFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;
import org.springframework.util.StringUtils;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
@Slf4j
public class JobVectorRepository {

    private static final String TABLE = "job_detail_vector";
    private static final Map<String, String> SORT_COLUMNS = Map.of(
            "salary", "avg",
            "updatedAt", "last_seen",
            "createdAt", "first_seen"
    );
    private static final String LIST_COLUMNS = """
            job_key, title, company, city, salary, avg, tier, exp, edu,
            cats_json::text AS cats_json, url, source, first_seen, last_seen, is_new, vector_ready,
            metadata ->> 'jobId' AS boss_job_id,
            metadata ->> 'salaryMin' AS salary_min,
            metadata ->> 'salaryMax' AS salary_max,
            metadata ->> 'salaryUnit' AS salary_unit,
            metadata ->> 'sourceSite' AS source_site,
            metadata ->> 'updatedAtRaw' AS updated_at_raw
            """;

    private final JdbcTemplate vectorJdbcTemplate;
    private final ObjectMapper objectMapper;
    private final RowMapper<JobVectorItem> listRowMapper = this::mapRow;

    public JobVectorRepository(@Qualifier("vectorJdbcTemplate") JdbcTemplate vectorJdbcTemplate,
                               ObjectMapper objectMapper) {
        this.vectorJdbcTemplate = vectorJdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public record PageResult(long total, List<JobVectorItem> list) {
    }

    public PageResult queryPage(JobVectorFilter filter) {
        JobVectorFilter actualFilter = filter == null ? new JobVectorFilter() : filter;
        StringBuilder where = new StringBuilder(" WHERE 1 = 1");
        List<Object> args = new ArrayList<>();
        appendConditions(where, args, actualFilter);

        Long total = vectorJdbcTemplate.queryForObject(
                "SELECT count(*) FROM " + TABLE + where, Long.class, args.toArray());
        if (total == null || total == 0) {
            log.info("[PG查询] 岗位分页查询完成, total=0");
            return new PageResult(0, List.of());
        }

        int page = positiveOrDefault(actualFilter.getPage(), 1);
        int pageSize = positiveOrDefault(actualFilter.getPageSize(), 20);
        String sortColumn = SORT_COLUMNS.getOrDefault(trimToEmpty(actualFilter.getSortBy()), "last_seen");
        String sortDirection = "asc".equalsIgnoreCase(trimToEmpty(actualFilter.getSortOrder())) ? "ASC" : "DESC";

        List<Object> pageArgs = new ArrayList<>(args);
        pageArgs.add(pageSize);
        pageArgs.add((page - 1) * pageSize);
        String sql = "SELECT " + LIST_COLUMNS + " FROM " + TABLE + where
                + " ORDER BY " + sortColumn + " " + sortDirection + " NULLS LAST, id DESC LIMIT ? OFFSET ?";
        List<JobVectorItem> jobs = vectorJdbcTemplate.query(sql, listRowMapper, pageArgs.toArray());
        log.info("[PG查询] 岗位分页查询完成, total={}, page={}, pageSize={}, returned={}",
                total, page, pageSize, jobs.size());
        return new PageResult(total, jobs);
    }

    public JobVectorItem findByJobKey(String jobKey) {
        if (!StringUtils.hasText(jobKey)) {
            return null;
        }
        List<JobVectorItem> rows = vectorJdbcTemplate.query(
                "SELECT " + LIST_COLUMNS + ", content FROM " + TABLE + " WHERE job_key = ?",
                listRowMapper, jobKey.trim());
        log.info("[PG查询] 岗位详情查询完成, jobId={}, found={}", jobKey, !rows.isEmpty());
        return rows.isEmpty() ? null : rows.get(0);
    }

    public Map<String, JobVectorItem> findByJobKeys(List<String> jobKeys) {
        List<String> keys = jobKeys == null ? List.of() : jobKeys.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .distinct()
                .toList();
        if (keys.isEmpty()) {
            return Map.of();
        }
        String placeholders = String.join(", ", Collections.nCopies(keys.size(), "?"));
        List<JobVectorItem> jobs = vectorJdbcTemplate.query(
                "SELECT " + LIST_COLUMNS + " FROM " + TABLE + " WHERE job_key IN (" + placeholders + ")",
                listRowMapper, keys.toArray());
        Map<String, JobVectorItem> byId = new LinkedHashMap<>();
        jobs.forEach(job -> byId.put(job.getJobId(), job));
        log.info("[PG查询] 收藏岗位批量补全完成, requested={}, found={}", keys.size(), jobs.size());
        return byId;
    }

    public Map<String, List<String>> loadFilterOptions() {
        Map<String, List<String>> options = new LinkedHashMap<>();
        options.put("city", distinctColumn("city"));
        options.put("edu", distinctColumn("edu"));
        options.put("exp", distinctColumn("exp"));
        options.put("tier", distinctColumn("tier"));
        log.info("[PG查询] 岗位筛选项查询完成, cities={}, education={}, exps={}, tiers={}",
                options.get("city").size(), options.get("edu").size(),
                options.get("exp").size(), options.get("tier").size());
        return options;
    }

    private List<String> distinctColumn(String column) {
        String sql = "SELECT DISTINCT " + column + " AS v FROM " + TABLE
                + " WHERE " + column + " IS NOT NULL AND " + column + " <> '' ORDER BY v";
        return vectorJdbcTemplate.query(sql, (rs, rowNum) -> rs.getString("v"));
    }

    private void appendConditions(StringBuilder where, List<Object> args, JobVectorFilter filter) {
        if (StringUtils.hasText(filter.getKeyword())) {
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
        List<String> cleaned = values == null ? List.of() : values.stream()
                .filter(StringUtils::hasText)
                .map(String::trim)
                .toList();
        if (cleaned.isEmpty()) {
            return;
        }
        where.append(" AND ").append(column).append(" IN (")
                .append(String.join(", ", Collections.nCopies(cleaned.size(), "?"))).append(")");
        args.addAll(cleaned);
    }

    private JobVectorItem mapRow(ResultSet rs, int rowNum) throws SQLException {
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
        item.setIsNew(rs.getObject("is_new") != null && rs.getBoolean("is_new"));
        item.setVectorReady(rs.getObject("vector_ready") == null ? Boolean.FALSE : rs.getBoolean("vector_ready"));
        item.setJobDescription(readOptionalString(rs, "content"));
        return item;
    }

    private List<String> parseCats(String json) {
        if (!StringUtils.hasText(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() { });
        } catch (Exception exception) {
            log.warn("[PG映射] cats_json 解析失败, value={}", json, exception);
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
        } catch (NumberFormatException exception) {
            log.debug("[PG映射] 数值字段无法转换, value={}", value);
            return null;
        }
    }

    private String readOptionalString(ResultSet rs, String column) {
        try {
            return rs.getString(column);
        } catch (SQLException ignored) {
            return null;
        }
    }

    private String toIsoText(ResultSet rs, String column) throws SQLException {
        OffsetDateTime dateTime = rs.getObject(column, OffsetDateTime.class);
        if (dateTime != null) {
            return dateTime.toString();
        }
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant().toString();
    }

    private int positiveOrDefault(Integer value, int defaultValue) {
        return value != null && value > 0 ? value : defaultValue;
    }

    private String trimToEmpty(String value) {
        return StringUtils.hasText(value) ? value.trim() : "";
    }

    private String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
