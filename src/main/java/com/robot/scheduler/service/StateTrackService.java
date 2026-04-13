package com.robot.scheduler.service;

import com.robot.scheduler.entity.TaskRecord;

public interface StateTrackService {
    // 记录任务状态变更
    void recordTaskStateChange(String taskId, String oldStatus, String newStatus, String reason);

    // 获取任务状态历史
    java.util.List<TaskRecord> getTaskStateHistory(String taskId);

    // 更新机器人状态
    void updateRobotState(String robotId, String status);
}
