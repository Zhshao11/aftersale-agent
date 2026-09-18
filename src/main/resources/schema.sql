-- 售后 Agent 核心表结构（MySQL 8）
-- 金额一律用「美分 BIGINT」存储，避免浮点误差；货币固定 USD

-- 订单表（种子数据，模拟电商订单）
CREATE TABLE IF NOT EXISTS orders (
    id            BIGINT AUTO_INCREMENT PRIMARY KEY,
    order_no      VARCHAR(32)  NOT NULL,
    user_id       VARCHAR(32)  NOT NULL,
    status        VARCHAR(16)  NOT NULL COMMENT 'PAID/SHIPPED/DELIVERED/CANCELLED/REFUNDED/EXCHANGED',
    item_name     VARCHAR(128) NOT NULL,
    amount_cents  BIGINT       NOT NULL,
    currency      VARCHAR(8)   NOT NULL DEFAULT 'USD',
    logistics_status VARCHAR(32) NULL COMMENT 'IN_TRANSIT/DELIVERED/NOT_SHIPPED',
    paid_at       DATETIME     NULL,
    shipped_at    DATETIME     NULL,
    delivered_at  DATETIME     NULL,
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    UNIQUE KEY uk_order_no (order_no),
    KEY idx_user (user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 会话与消息
CREATE TABLE IF NOT EXISTS conversations (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    user_id     VARCHAR(32) NOT NULL,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS conversation_messages (
    id              BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    role            VARCHAR(16) NOT NULL COMMENT 'USER/ASSISTANT/SYSTEM/TOOL',
    content         TEXT NOT NULL,
    created_at      DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Plan（写操作必须先落 Plan，确认后才可执行）
CREATE TABLE IF NOT EXISTS plans (
    id                    BIGINT AUTO_INCREMENT PRIMARY KEY,
    conversation_id       BIGINT NOT NULL,
    user_id               VARCHAR(32) NOT NULL,
    order_no              VARCHAR(32) NULL,
    summary               VARCHAR(512) NOT NULL COMMENT '面向用户的自然语言描述',
    estimated_amount_cents BIGINT NULL COMMENT '预估金额（触发 >=500 双确认）',
    context_fingerprint   VARCHAR(128) NOT NULL COMMENT '上下文指纹：订单状态+参数哈希',
    status                VARCHAR(24) NOT NULL DEFAULT 'PENDING_CONFIRM'
        COMMENT 'PENDING_CONFIRM/AWAITING_SECOND_CONFIRM/CONFIRMED/EXECUTING/COMPLETED/FAILED/CLOSED/EXPIRED',
    second_confirm_required TINYINT(1) NOT NULL DEFAULT 0,
    created_at            DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    confirmed_at          DATETIME NULL,
    KEY idx_conv (conversation_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Plan 步骤
CREATE TABLE IF NOT EXISTS plan_steps (
    id          BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id     BIGINT NOT NULL,
    seq         INT NOT NULL,
    tool_name   VARCHAR(64) NOT NULL,
    args_json   JSON NOT NULL,
    risk_level  VARCHAR(16) NOT NULL DEFAULT 'WRITE' COMMENT 'READ/WRITE',
    status      VARCHAR(16) NOT NULL DEFAULT 'PENDING'
        COMMENT 'PENDING/RUNNING/SUCCESS/FAILED/UNKNOWN',
    result_json JSON NULL,
    attempt     INT NOT NULL DEFAULT 0,
    created_at  DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    UNIQUE KEY uk_plan_seq (plan_id, seq)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 执行日志（断点续跑 + 可观测性：每步耗时/token）
CREATE TABLE IF NOT EXISTS execution_log (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    plan_id      BIGINT NOT NULL,
    step_id      BIGINT NOT NULL,
    attempt      INT NOT NULL,
    idempotency_key VARCHAR(128) NOT NULL,
    status       VARCHAR(16) NOT NULL COMMENT 'SUCCESS/FAILED/UNKNOWN',
    error_code   VARCHAR(64) NULL,
    detail       TEXT NULL,
    latency_ms   BIGINT NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    KEY idx_plan (plan_id),
    KEY idx_step (step_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 幂等键表：唯一索引 = 构造性防重放
CREATE TABLE IF NOT EXISTS idempotency_keys (
    idempotency_key VARCHAR(128) PRIMARY KEY COMMENT 'planId:stepId:attempt',
    plan_id      BIGINT NOT NULL,
    step_id      BIGINT NOT NULL,
    attempt      INT NOT NULL,
    result_status VARCHAR(16) NULL COMMENT '执行结果三态，供冲突时回放',
    result_json  JSON NULL,
    created_at   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- 政策规则表（基线与完整版共用）
CREATE TABLE IF NOT EXISTS policy_rules (
    id             BIGINT AUTO_INCREMENT PRIMARY KEY,
    rule_code      VARCHAR(64) NOT NULL,
    scope          VARCHAR(16) NOT NULL COMMENT 'CANCEL/REFUND/EXCHANGE',
    rule_name      VARCHAR(128) NOT NULL,
    allowed_statuses VARCHAR(128) NOT NULL COMMENT '允许前置状态，逗号分隔',
    within_days    INT NULL COMMENT '送达后窗口天数，NULL 表示不限',
    enabled        TINYINT(1) NOT NULL DEFAULT 1,
    UNIQUE KEY uk_rule_code (rule_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
