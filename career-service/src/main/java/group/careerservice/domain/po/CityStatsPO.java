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
@TableName(value = "city_stats", autoResultMap = true)
public class CityStatsPO implements Serializable {

    private static final long serialVersionUID = 1L;

    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    @TableField("province_adcode")
    private Integer provinceAdcode;

    @TableField("province_name")
    private String provinceName;

    @TableField("city_name")
    private String cityName;

    @TableField("city_adcode")
    private Integer cityAdcode;

    @TableField("job_count")
    private Integer jobCount;

    @TableField("jd_count")
    private Integer jdCount;

    @TableField("avg_salary_monthly")
    private BigDecimal avgSalaryMonthly;

    @TableField("tier")
    private String tier;

    @TableField(value = "top_jobs", typeHandler = JacksonTypeHandler.class)
    private List<Map<String, Object>> topJobs;

    @TableField("updated_at")
    private LocalDateTime updatedAt;
}
