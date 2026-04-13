package com.robot.scheduler.service;

public interface ScheduleService {
    // 触发调度
    void triggerSchedule();

    // 分配任务给机器人
    boolean assignTask(String taskId, String robotId);

    // 处理机器人故障
    void handleRobotError(String robotId);
}
