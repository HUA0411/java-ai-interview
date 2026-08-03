-- ============ AI 面试模拟系统 建表脚本（幂等，可重复执行） ============

CREATE DATABASE IF NOT EXISTS ai_interview DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
USE ai_interview;

CREATE TABLE IF NOT EXISTS `user` (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    username      VARCHAR(50)  NOT NULL UNIQUE COMMENT '用户名',
    password_hash VARCHAR(100) NOT NULL COMMENT 'BCrypt 加密后的密码',
    created_at    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB COMMENT ='用户表';

CREATE TABLE IF NOT EXISTS interview_session (
    id             BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    user_id        BIGINT UNSIGNED NOT NULL COMMENT '所属用户',
    direction      VARCHAR(20)     NOT NULL COMMENT '面试方向: JAVA/FRONTEND/AI',
    status         VARCHAR(20)     NOT NULL DEFAULT 'ONGOING' COMMENT 'ONGOING/FINISHED',
    question_count INT             NOT NULL DEFAULT 0 COMMENT '已问题目数',
    created_at     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    finished_at    DATETIME        NULL,
    INDEX idx_user (user_id)
) ENGINE = InnoDB COMMENT ='面试会话表';

CREATE TABLE IF NOT EXISTS interview_message (
    id         BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    session_id BIGINT UNSIGNED NOT NULL COMMENT '所属会话',
    role       VARCHAR(10)     NOT NULL COMMENT 'AI/USER',
    content    TEXT            NOT NULL COMMENT '消息内容',
    created_at DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    INDEX idx_session (session_id)
) ENGINE = InnoDB COMMENT ='会话消息表';

CREATE TABLE IF NOT EXISTS interview_report (
    id            BIGINT UNSIGNED AUTO_INCREMENT PRIMARY KEY,
    session_id    BIGINT UNSIGNED NOT NULL UNIQUE COMMENT '所属会话',
    overall_score INT             NOT NULL COMMENT '总分(0-100)',
    qa_reviews    TEXT            NOT NULL COMMENT '逐题点评(JSON数组)',
    summary       TEXT            NOT NULL COMMENT '综合评语',
    suggestions   TEXT            NOT NULL COMMENT '改进建议',
    created_at    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE = InnoDB COMMENT ='面试报告表';
