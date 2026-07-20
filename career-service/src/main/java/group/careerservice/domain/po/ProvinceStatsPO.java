package group.careerservice.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.baomidou.mybatisplus.extension.handlers.JacksonTypeHandler;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName(value = "province_stats", autoResultMap = true)
public class ProvinceStatsPO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("province_name")
    private String provinceName;

    @TableField("province_adcode")
    private Integer provinceAdcode;

    @TableField("job_count")
    private Integer jobCount;

    @TableField("jd_count")
    private Integer jdCount;

    @TableField("city_count")
    private Integer cityCount;

    @TableField("avg_salary_monthly")
    private BigDecimal avgSalaryMonthly;

    @TableField(value = "top_jobs", typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> topJobs;

    @TableField(value = "tier_distribution", typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> tierDistribution;

    @TableField("created_at")
    private LocalDateTime createdAt;
}
