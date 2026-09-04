-- =====================================================================
-- AI 职业规划专家 · 行动方案表（user_career_plans）
-- 设计见 fc2026/职业规划专家方案.md
-- 落点：career-service 数据源连接的 MySQL，已建在 careers 库（application-local.yaml 的
--       fc.db.database=careers，192.168.118.130）。表只跟随 career-service，与 profile-service
--       的 user_profile 库无关；若某环境 career-service 数据源指向其它库，需在该库执行本 DDL。
-- 用途：AI 规划专家生成的方案持久化。用户可多次规划，新版本插入时旧版置 archived（不物理删）。
--       本期不做逐条勾选，content 存整份 markdown 全文。
-- =====================================================================
CREATE TABLE IF NOT EXISTS user_career_plans (
  id                  BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '自增主键',
  user_id             BIGINT          NOT NULL                COMMENT '用户ID（硬隔离，绝不可少）',
  session_id          VARCHAR(64)     NULL                    COMMENT '来源会话 id（溯源用，可空）',
  status              VARCHAR(16)     NOT NULL DEFAULT 'active' COMMENT 'active=当前方案 / archived=历史版本',
  title               VARCHAR(200)    NOT NULL                COMMENT '方案标题（列表/入口展示）',
  content             LONGTEXT        NOT NULL                COMMENT '方案 markdown 全文',
  supersedes_plan_id  BIGINT          NULL                    COMMENT '版本链：本方案取代的旧方案 id（可空）',
  created_at          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
  PRIMARY KEY (id),
  KEY idx_plan_user_status (user_id, status, created_at)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COMMENT = 'AI 职业规划专家生成的行动方案';
