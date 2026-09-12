package group.resumeparserservice.domain.po;/* I love coding */

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;

// 新增：domain/po/AgentTaskPO.java
@Data
@RequiredArgsConstructor
@AllArgsConstructor
public class AgentTaskPO {
    private String taskId;          // hat_30001
    private Long userId;            // 用户 ID
    private String goal;            // 任务目标
    private String mode;            // execution/planning
    private String priority;        // normal/high/urgent
    private String status;          // planning/running/paused/completed/failed/canceled
    private String planJson;        // 计划步骤 JSON
    private String contextJson;     // 上下文数据
    private String constraintsJson; // 约束条件
    private String resultJson;      // 执行结果
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
