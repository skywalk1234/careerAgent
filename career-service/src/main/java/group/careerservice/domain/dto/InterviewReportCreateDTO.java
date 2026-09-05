package group.careerservice.domain.dto;/* I love coding */

import lombok.Data;

/**
 * 创建面试总结报告请求体（Python 模拟面试专家报告工具经网关 POST 落库）。
 * jobId/jobName/companyName 由服务端从开场 JD 快照直接回填，不由报告 LLM 产出。
 */
@Data
public class InterviewReportCreateDTO {
    /** 报告标题 */
    private String title;
    /** 报告 markdown 全文（含 # 标题行） */
    private String content;
    /** 来源面试会话 id（溯源用，可空） */
    private String sessionId;
    /** 意向岗位 id（快照，可空） */
    private String jobId;
    /** 岗位名快照（可空） */
    private String jobName;
    /** 公司名快照（可空） */
    private String companyName;
}
