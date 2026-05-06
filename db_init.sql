-- Robot Scheduler 数据库初始化脚本
-- 基于现有实体类生成，MySQL 8.0+
-- 数据库名：robot_scheduler

CREATE DATABASE IF NOT EXISTS robot_scheduler
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE robot_scheduler;

-- ----------------------------
-- 表：robot（机器人信息）
-- ----------------------------
DROP TABLE IF EXISTS robot;
CREATE TABLE robot
(
    robot_id        VARCHAR(36)     NOT NULL COMMENT '机器人ID（程序生成UUID）',
    robot_name      VARCHAR(64)     NULL COMMENT '机器人名称',
    robot_code      VARCHAR(64)     NULL COMMENT '机器人编码',
    status          VARCHAR(16)     NOT NULL DEFAULT '空闲' COMMENT '状态：空闲 / 忙碌 / 故障',
    load            INT             NOT NULL DEFAULT 0 COMMENT '当前负载',
    last_heartbeat  DATETIME        NULL COMMENT '最后心跳时间',
    battery         INT             NULL COMMENT '电池电量（百分比）',
    x               DOUBLE          NULL COMMENT '位置X坐标（米）',
    y               DOUBLE          NULL COMMENT '位置Y坐标（米）',
    yaw             DOUBLE          NULL COMMENT '朝向角度（弧度）',
    PRIMARY KEY (robot_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='机器人信息表';

-- ----------------------------
-- 表：task（任务信息）
-- ----------------------------
DROP TABLE IF EXISTS task;
CREATE TABLE task
(
    task_id                 VARCHAR(36)     NOT NULL COMMENT '任务ID（程序生成UUID）',
    task_name               VARCHAR(128)    NULL COMMENT '任务名称',
    command_type            VARCHAR(64)     NULL COMMENT '指令类型',
    priority                INT             NOT NULL DEFAULT 3 COMMENT '优先级 1-5，1最高',
    robot_id                VARCHAR(32)     NULL COMMENT '分配到的机器人ID',
    robot_code              VARCHAR(64)     NULL COMMENT '机器人编码',
    status                  VARCHAR(16)     NOT NULL DEFAULT 'QUEUED' COMMENT '状态：QUEUED / RUNNING / SUCCESS / FAILED',
    task_params             JSON            NULL COMMENT '任务参数（JSON格式）',
    create_time             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    start_time              DATETIME        NULL COMMENT '开始执行时间',
    finish_time             DATETIME        NULL COMMENT '完成时间',
    fail_reason             VARCHAR(512)    NULL COMMENT '失败原因',
    deadline                DATETIME        NULL COMMENT '任务截止时间（动态优先级规划）',
    estimated_duration      INT             NULL COMMENT '预估执行时长（秒）',
    dynamic_priority_score  DOUBLE          NULL COMMENT '动态优先级分数（越低越优先）',
    PRIMARY KEY (task_id),
    INDEX idx_status (status),
    INDEX idx_robot_id (robot_id),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='任务信息表';

-- ----------------------------
-- 表：task_record（任务状态流转记录）
-- ----------------------------
DROP TABLE IF EXISTS task_record;
CREATE TABLE task_record
(
    record_id       VARCHAR(36)     NOT NULL COMMENT '记录ID（程序生成UUID）',
    task_id         VARCHAR(32)     NOT NULL COMMENT '关联任务ID',
    old_status      VARCHAR(16)     NULL COMMENT '变更前状态',
    new_status      VARCHAR(16)     NULL COMMENT '变更后状态',
    change_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    change_reason   VARCHAR(512)    NULL COMMENT '变更原因',
    PRIMARY KEY (record_id),
    INDEX idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='任务状态变更记录表';

-- ----------------------------
-- 表：log（系统日志）
-- ----------------------------
DROP TABLE IF EXISTS log;
CREATE TABLE log
(
    log_id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '日志ID（自增）',
    log_type        VARCHAR(32)     NULL COMMENT '日志类型：TASK / ROBOT / SYSTEM',
    message         TEXT            NULL COMMENT '日志内容',
    reference_id    VARCHAR(36)     NULL COMMENT '关联ID（任务ID或机器人ID）',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (log_id),
    INDEX idx_log_type (log_type),
    INDEX idx_reference_id (reference_id),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='系统日志表';

-- ----------------------------
-- 表：map（SLAM 地图信息）
-- ----------------------------
DROP TABLE IF EXISTS map;
CREATE TABLE map
(
    map_id          VARCHAR(36)     NOT NULL COMMENT '地图ID（程序生成UUID）',
    map_name        VARCHAR(100)    NOT NULL COMMENT '地图名称',
    pgm_data        LONGBLOB        NULL COMMENT 'PGM 栅格图二进制数据',
    yaml_data       TEXT            NULL COMMENT 'YAML 元数据文本',
    resolution      DOUBLE          NULL COMMENT '分辨率（米/像素）',
    origin_x        DOUBLE          NULL COMMENT '地图原点 X（米）',
    origin_y        DOUBLE          NULL COMMENT '地图原点 Y（米）',
    origin_yaw      DOUBLE          NULL COMMENT '地图原点偏航角（弧度）',
    width           INT             NULL COMMENT '地图宽度（像素）',
    height          INT             NULL COMMENT '地图高度（像素）',
    negate          INT             NULL COMMENT '是否反转像素值 0/1',
    occupied_thresh DOUBLE          NULL COMMENT '占用阈值',
    free_thresh     DOUBLE          NULL COMMENT '空闲阈值',
    is_active       TINYINT         NOT NULL DEFAULT 0 COMMENT '是否当前激活 0/1',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (map_id),
    INDEX idx_is_active (is_active)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='SLAM 地图表';

-- ----------------------------
-- 表：map_live（SLAM 实时地图快照）
-- ----------------------------
DROP TABLE IF EXISTS map_live;
CREATE TABLE map_live
(
    live_id         VARCHAR(36)     NOT NULL COMMENT '实时地图ID（固定为 current）',
    map_id          VARCHAR(32)     NULL COMMENT '关联的静态地图ID',
    resolution      DOUBLE          NULL COMMENT '分辨率（米/像素）',
    width           INT             NULL COMMENT '地图宽度（像素）',
    height          INT             NULL COMMENT '地图高度（像素）',
    origin_x        DOUBLE          NULL COMMENT '地图原点 X（米）',
    origin_y        DOUBLE          NULL COMMENT '地图原点 Y（米）',
    origin_yaw      DOUBLE          NULL COMMENT '地图原点偏航角（弧度）',
    grid_data       LONGTEXT        NULL COMMENT '实时栅格数据（JSON 数组）',
    obstacles       LONGTEXT        NULL COMMENT '障碍物列表（JSON）',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (live_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='SLAM 实时地图快照表';


-- ----------------------------
-- 表：vision_detection（视觉识别结果）
-- ----------------------------
DROP TABLE IF EXISTS vision_detection;
CREATE TABLE vision_detection
(
    detection_id    VARCHAR(36)     NOT NULL COMMENT '识别记录ID（程序生成UUID）',
    obj_name        VARCHAR(64)     NULL COMMENT '物体名称',
    cx              INT             NULL COMMENT '像素坐标X',
    cy              INT             NULL COMMENT '像素坐标Y',
    x               DOUBLE          NULL COMMENT '世界坐标X / 深度图X',
    y               DOUBLE          NULL COMMENT '世界坐标Y / 深度图Y',
    z               DOUBLE          NULL COMMENT '深度值Z（米）',
    confidence      DOUBLE          NULL COMMENT '置信度',
    source          VARCHAR(32)     NULL DEFAULT 'websocket' COMMENT '数据来源',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (detection_id),
    INDEX idx_obj_name (obj_name),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='视觉识别结果表';

-- ----------------------------
-- 表：motion_kinematics（运动学正逆解结果）
-- ----------------------------
DROP TABLE IF EXISTS motion_kinematics;
CREATE TABLE motion_kinematics
(
    kinematics_id   VARCHAR(36)     NOT NULL COMMENT '运动学记录ID（程序生成UUID）',
    detection_id    VARCHAR(36)     NULL COMMENT '关联视觉识别ID',
    task_id         VARCHAR(36)     NULL COMMENT '关联生成的任务ID',
    fk_target_pose  JSON            NULL COMMENT '正运动学末端位姿（JSON）',
    ik_solve        TINYINT         NULL DEFAULT 0 COMMENT '逆运动学是否求解成功 0/1',
    ik_joint_angles JSON            NULL COMMENT '逆运动学关节角（JSON数组）',
    ik_message      VARCHAR(256)    NULL COMMENT '逆运动学求解信息',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (kinematics_id),
    INDEX idx_detection_id (detection_id),
    INDEX idx_task_id (task_id)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='运动学结果表';

-- ----------------------------
-- 表：llm_communication（LLM 通信记录）
-- ----------------------------
DROP TABLE IF EXISTS llm_communication;
CREATE TABLE llm_communication
(
    msg_id           VARCHAR(36)     NOT NULL COMMENT '消息记录ID（程序生成UUID）',
    action           VARCHAR(64)     NULL COMMENT '动作类型：parse_natural_language / combine_tasks / get_behavior_tree_status / execute_behavior_node 等',
    request_content  TEXT            NULL COMMENT '发送给LLM的请求内容',
    response_content TEXT            NULL COMMENT 'LLM返回的响应内容',
    status           VARCHAR(16)     NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / SUCCESS / FAILED',
    duration_ms      INT             NULL COMMENT '通信耗时（毫秒）',
    create_time      DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (msg_id),
    INDEX idx_action (action),
    INDEX idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='LLM通信记录表';

-- ----------------------------
-- 表：ros_navigation_goal（ROS 导航目标记录）
-- ----------------------------
DROP TABLE IF EXISTS ros_navigation_goal;
CREATE TABLE ros_navigation_goal
(
    goal_id         VARCHAR(36)     NOT NULL COMMENT '目标ID（程序生成UUID）',
    robot_id        VARCHAR(36)     NULL COMMENT '机器人ID',
    robot_code      VARCHAR(64)     NULL COMMENT '机器人编码',
    x               DOUBLE          NOT NULL COMMENT '目标X坐标（米）',
    y               DOUBLE          NOT NULL COMMENT '目标Y坐标（米）',
    yaw             DOUBLE          NULL COMMENT '目标偏航角（弧度）',
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING' COMMENT '状态：PENDING / ACTIVE / COMPLETED / FAILED',
    send_time       DATETIME        NULL COMMENT '发送时间',
    complete_time   DATETIME        NULL COMMENT '完成时间',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (goal_id),
    INDEX idx_robot_id (robot_id),
    INDEX idx_status (status)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='ROS导航目标表';

-- ----------------------------
-- 表：robot_pose_history（机器人位姿历史）
-- ----------------------------
DROP TABLE IF EXISTS robot_pose_history;
CREATE TABLE robot_pose_history
(
    history_id      BIGINT          NOT NULL AUTO_INCREMENT COMMENT '历史记录ID',
    robot_id        VARCHAR(36)     NULL COMMENT '机器人ID',
    x               DOUBLE          NULL COMMENT '位置X坐标（米）',
    y               DOUBLE          NULL COMMENT '位置Y坐标（米）',
    yaw             DOUBLE          NULL COMMENT '朝向角度（弧度）',
    source          VARCHAR(32)     NULL DEFAULT 'ROS_HTTP' COMMENT '数据来源：ROS_HTTP / MANUAL / TEST',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '记录时间',
    PRIMARY KEY (history_id),
    INDEX idx_robot_id (robot_id),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci COMMENT ='机器人位姿历史表';
