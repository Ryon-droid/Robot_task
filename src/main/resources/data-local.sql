INSERT INTO robot (robot_id, robot_name, robot_code, status, load, battery, x, y, yaw) VALUES
('R-001', '机器人Alpha', 'RB001', '空闲', 0, 87, 1.0, 2.0, 0.5),
('R-002', '机器人Beta',  'RB002', '空闲', 0, 62, 3.5, 1.2, 1.0),
('R-003', '机器人Gamma', 'RB003', '忙碌', 1, 45, 5.0, 5.0, 2.0),
('R-004', '机器人Delta', 'RB004', '空闲', 0, 78, 2.0, 3.0, 0.0);

INSERT INTO task (task_id, task_name, command_type, priority, robot_id, robot_code, status, task_params, create_time, estimated_duration, dynamic_priority_score) VALUES
('t001', '搬运任务', 'MOVE',   2, 'R-001', 'RB001', 'RUNNING', '{"target":"A1"}',  CURRENT_TIMESTAMP, 300,  0.0),
('t002', '巡检任务', 'PATROL', 3, 'R-002', 'RB002', 'QUEUED',  '{"area":"B2"}',  CURRENT_TIMESTAMP, 600,  0.0),
('t003', '充电任务', 'CHARGE', 1, 'R-003', 'RB003', 'QUEUED',  '{"station":"S1"}', CURRENT_TIMESTAMP, 1200, 0.0),
('t004', '清洁任务', 'CLEAN',  2, 'R-004', 'RB004', 'QUEUED',  '{"path":"C3"}',  CURRENT_TIMESTAMP, 450,  0.0);
