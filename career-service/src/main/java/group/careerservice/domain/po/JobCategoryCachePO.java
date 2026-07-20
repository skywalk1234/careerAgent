package group.careerservice.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job_category_cache")
public class JobCategoryCachePO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String batchId;

    private String categoryName;

    private String description;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
