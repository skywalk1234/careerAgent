package group.careerservice.domain.po;/* I love coding */

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.experimental.Accessors;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * AI 模拟面试专家生成的面试总结报告（表 user_interview_reports，落在 career-service 数据源连接的 MySQL）。
 * 设计见 fc2026/模拟面试专家方案.md：一场面试一份报告，不版本化；content 存整篇 markdown 全文。
 * 由 Python ai-service 报告工具（submit_mock_interview_report）经网关 POST 落库。
 */
@Data
@EqualsAndHashCode(callSuper = false)
@Accessors(chain = true)
@TableName("user_interview_reports")
public class InterviewReport implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 自增主键 */
    @TableId(value = "id", type = IdType.AUTO)
    private Long id;

    /** 用户ID（硬隔离，绝不可少） */
    @TableField("user_id")
    private Long userId;

    /** 来源面试会话 id（Python chat_history 库 interview_sessions，溯源用，可空） */
    @TableField("session_id")
    private String sessionId;

    /** 意向岗位 id（快照） */
    @TableField("job_id")
    private String jobId;

    /** 岗位名快照（列表展示，防岗位被删后仍可读） */
    @TableField("job_name")
    private String jobName;

    /** 公司名快照 */
    @TableField("company_name")
    private String companyName;

    /** 报告标题（列表/入口展示） */
    @TableField("title")
    private String title;

    /** 报告 markdown 全文 */
    @TableField("content")
    private String content;

    /** 创建时间 */
    @TableField("created_at")
    private LocalDateTime createdAt;
}
