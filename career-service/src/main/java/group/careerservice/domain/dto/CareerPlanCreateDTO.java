package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;

/**
 * 创建行动方案请求体（Python 职业规划专家生成后经网关 POST 落库）。
 */
@Data
public class CareerPlanCreateDTO {
    /** 方案标题 */
    private String title;
    /** 方案 markdown 全文 */
    private String content;
    /** 来源会话 id（溯源用，可空） */
    private String sessionId;
}
