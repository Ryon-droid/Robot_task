package com.robot.scheduler.service;

/**
 * 任务优先级规划器
 * 基于多因素动态计算任务优先级分数
 */
public interface TaskPriorityPlanner {

    /**
     * 重新计算所有待执行（QUEUED）任务的动态优先级分数，并写回数据库
     *
     * @return 重算的任务数量
     */
    int recalculateAllPendingTasks();
}
