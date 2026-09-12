package group.careerservice.domain.po;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job_import_task")
public class JobImportTaskPO {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String importJobId;

    private String batchId;

    private String batchName;

    private String sourceType;

    private String sourceFile;

    private String status;

    private Integer totalRows;

    private Integer insertedRows;

    private Integer updatedRows;

    private Integer skippedRows;

    private String errorMessage;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    private LocalDateTime completedAt;
}
