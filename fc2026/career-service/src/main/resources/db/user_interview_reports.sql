-- =====================================================================
-- 模拟面试专家 · 面试总结报告表（user_interview_reports）
-- 设计见 fc2026/模拟面试专家方案.md §3.2
-- 落点：career-service 数据源连接的 MySQL（与 user_career_plans 同库，application-local.yaml 的
--       fc.db.database=careers，192.168.118.130）。表只跟随 career-service。
-- 用途：AI 模拟面试专家生成的面试总结报告持久化（Python ai-service 报告工具经网关 POST 落库）。
--       一场面试一份报告，不版本化、无 status/supersedes 字段。
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_interview_reports (
  id            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  user_id       BIGINT          NOT NULL                COMMENT '用户ID（硬隔离）',
  session_id    VARCHAR(64)     NULL                    COMMENT '来源面试会话 id（Python chat_history，溯源用）',
  job_id        VARCHAR(64)     NULL                    COMMENT '意向岗位 id（快照）',
  job_name      VARCHAR(200)    NULL                    COMMENT '岗位名快照（列表展示，防岗位被删）',
  company_name  VARCHAR(200)    NULL                    COMMENT '公司名快照',
  title         VARCHAR(200)    NOT NULL                COMMENT '报告标题（列表/入口展示）',
  content       LONGTEXT        NOT NULL                COMMENT '报告 markdown 全文',
  created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_report_user_time (user_id, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 模拟面试总结报告';
