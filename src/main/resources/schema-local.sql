CREATE TABLE IF NOT EXISTS robot
(
    robot_id        VARCHAR(36)     NOT NULL,
    robot_name      VARCHAR(64)     NULL,
    robot_code      VARCHAR(64)     NULL,
    status          VARCHAR(16)     NOT NULL DEFAULT '空闲',
    load            INT             NOT NULL DEFAULT 0,
    last_heartbeat  TIMESTAMP       NULL,
    battery         INT             NULL,
    x               DOUBLE          NULL,
    y               DOUBLE          NULL,
    yaw             DOUBLE          NULL,
    PRIMARY KEY (robot_id)
);

CREATE TABLE IF NOT EXISTS task
(
    task_id                 VARCHAR(36)     NOT NULL,
    task_name               VARCHAR(128)    NULL,
    command_type            VARCHAR(64)     NULL,
    priority                INT             NOT NULL DEFAULT 3,
    robot_id                VARCHAR(32)     NULL,
    robot_code              VARCHAR(64)     NULL,
    status                  VARCHAR(16)     NOT NULL DEFAULT 'QUEUED',
    task_params             CLOB            NULL,
    create_time             TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    start_time              TIMESTAMP       NULL,
    finish_time             TIMESTAMP       NULL,
    fail_reason             VARCHAR(512)    NULL,
    deadline                TIMESTAMP       NULL,
    estimated_duration      INT             NULL,
    dynamic_priority_score  DOUBLE          NULL,
    PRIMARY KEY (task_id)
);
CREATE INDEX IF NOT EXISTS idx_status ON task(status);
CREATE INDEX IF NOT EXISTS idx_robot_id ON task(robot_id);
CREATE INDEX IF NOT EXISTS idx_create_time ON task(create_time);

CREATE TABLE IF NOT EXISTS task_record
(
    record_id       VARCHAR(36)     NOT NULL,
    task_id         VARCHAR(32)     NOT NULL,
    old_status      VARCHAR(16)     NULL,
    new_status      VARCHAR(16)     NULL,
    change_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    change_reason   VARCHAR(512)    NULL,
    PRIMARY KEY (record_id)
);
CREATE INDEX IF NOT EXISTS idx_task_id ON task_record(task_id);

CREATE TABLE IF NOT EXISTS log
(
    log_id          BIGINT          NOT NULL AUTO_INCREMENT,
    log_type        VARCHAR(32)     NULL,
    message         CLOB            NULL,
    reference_id    VARCHAR(36)     NULL,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (log_id)
);
CREATE INDEX IF NOT EXISTS idx_log_type ON log(log_type);
CREATE INDEX IF NOT EXISTS idx_reference_id ON log(reference_id);
CREATE INDEX IF NOT EXISTS idx_create_time ON log(create_time);

CREATE TABLE IF NOT EXISTS map
(
    map_id          VARCHAR(36)     NOT NULL,
    map_name        VARCHAR(100)    NOT NULL,
    pgm_data        BLOB            NULL,
    yaml_data       CLOB            NULL,
    resolution      DOUBLE          NULL,
    origin_x        DOUBLE          NULL,
    origin_y        DOUBLE          NULL,
    origin_yaw      DOUBLE          NULL,
    width           INT             NULL,
    height          INT             NULL,
    negate          INT             NULL,
    occupied_thresh DOUBLE          NULL,
    free_thresh     DOUBLE          NULL,
    is_active       INT             NOT NULL DEFAULT 0,
    create_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (map_id)
);
CREATE INDEX IF NOT EXISTS idx_is_active ON map(is_active);

CREATE TABLE IF NOT EXISTS map_live
(
    live_id         VARCHAR(36)     NOT NULL,
    map_id          VARCHAR(32)     NULL,
    resolution      DOUBLE          NULL,
    width           INT             NULL,
    height          INT             NULL,
    origin_x        DOUBLE          NULL,
    origin_y        DOUBLE          NULL,
    origin_yaw      DOUBLE          NULL,
    grid_data       CLOB            NULL,
    obstacles       CLOB            NULL,
    update_time     TIMESTAMP       NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (live_id)
);
