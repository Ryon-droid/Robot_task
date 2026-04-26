-- 创建数据库
CREATE DATABASE robot_scheduler DEFAULT CHARACTER SET utf8mb4;
USE robot_scheduler;

-- 机器人表
CREATE TABLE robot (
    robot_id VARCHAR(32) PRIMARY KEY,
    robot_name VARCHAR(64) NOT NULL,
    robot_code VARCHAR(32) UNIQUE COMMENT '机器人编码，用于与ROS/LLM对接',
    status VARCHAR(16) DEFAULT '空闲',
    load INT DEFAULT 0,
    last_heartbeat DATETIME,
    battery INT DEFAULT 100,
    x DOUBLE,
    y DOUBLE,
    yaw DOUBLE
);

-- 任务表
CREATE TABLE task (
    task_id VARCHAR(32) PRIMARY KEY,
    task_name VARCHAR(64) NOT NULL,
    command_type VARCHAR(32),
    priority INT DEFAULT 3,
    robot_id VARCHAR(32),
    robot_code VARCHAR(32),
    status VARCHAR(16) DEFAULT 'QUEUED',
    task_params JSON,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    start_time DATETIME,
    finish_time DATETIME,
    fail_reason VARCHAR(255),
    deadline DATETIME NULL COMMENT '任务截止时间',
    estimated_duration INT DEFAULT 0 COMMENT '预估执行时长(秒)',
    dynamic_priority_score DOUBLE DEFAULT 0 COMMENT '动态优先级分数(越低越优先)'
);

-- 任务状态日志表
CREATE TABLE task_record (
    record_id VARCHAR(32) PRIMARY KEY,
    task_id VARCHAR(32),
    old_status VARCHAR(16),
    new_status VARCHAR(16),
    change_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    change_reason VARCHAR(255)
);

-- 日志表
CREATE TABLE log (
    log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_type VARCHAR(32),
    message TEXT,
    reference_id VARCHAR(32),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);