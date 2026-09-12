package group.careerservice.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job_edge_generate_task")
public class JobEdgeGenerateTaskPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String edgeJobId;

    private String batchId;

    private String status;

    private Integer generatedEdges;

    private Integer promotionEdges;

    private Integer transitionEdges;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
