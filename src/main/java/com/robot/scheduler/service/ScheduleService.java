package com.robot.scheduler.service;

public interface ScheduleService {
    // 触发调度
    void triggerSchedule();

    // 分配任务给机器人
    boolean assignTask(String taskId, String robotId);

    // 处理机器人故障
    void handleRobotError(String robotId);

    // 重新计算所有待执行任务的动态优先级
    void recalculatePriorities();

    // 从数据库刷新任务队列
    void refreshQueueFromDb();

    // 获取当前内存中的待执行任务队列（按优先级排序）
    java.util.List<com.robot.scheduler.entity.Task> getPendingQueue();
}
