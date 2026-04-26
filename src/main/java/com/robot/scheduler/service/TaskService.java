package com.robot.scheduler.service;

import com.robot.scheduler.entity.Task;

import java.util.List;

public interface TaskService {
    
    /**
     * 创建任务
     */
    Task createTask(Task task);
    
    /**
     * 获取任务列表
     */
    List<Task> getTaskList();
    
    /**
     * 获取任务列表（支持筛选）
     */
    List<Task> getTaskList(String status, String robotId);
    
    /**
     * 根据ID获取任务
     */
    Task getTaskById(String taskId);
    
    /**
     * 更新任务状态
     */
    boolean updateTaskStatus(String taskId, String status, String reason);
    
    /**
     * 删除任务
     */
    boolean deleteTask(String taskId);
    
    /**
     * 获取待执行任务
     */
    List<Task> getPendingTasks();

    /**
     * 取消任务
     */
    boolean cancelTask(String taskId);

    /**
     * 重新分配任务
     */
    boolean reassignTask(String taskId);

    /**
     * 调整任务优先级
     */
    boolean updateTaskPriority(String taskId, Integer priority);
}