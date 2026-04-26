package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.mapper.TaskMapper;
import com.robot.scheduler.service.DataServiceClient;
import com.robot.scheduler.service.LogService;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.TaskService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class TaskServiceImpl implements TaskService {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private RobotMapper robotMapper;

    @Autowired
    private LogService logService;

    @Autowired
    private TaskPriorityPlannerImpl taskPriorityPlanner;

    @Autowired
    private DataServiceClient dataServiceClient;

    @Autowired
    private ScheduleService scheduleService;

    @Override
    @Transactional
    public Task createTask(Task task) {
        if (task.getTaskId() == null) {
            task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        }
        task.setCreateTime(new Date());
        task.setStatus(StatusConstant.TASK_STATUS_PENDING);  // 默认为队列状态

        // 计算初始动态优先级分数后一次性写入
        List<Robot> robots = robotMapper.selectList(null);
        double score = taskPriorityPlanner.calculateScore(task, robots);
        task.setDynamicPriorityScore(score);
        taskMapper.insert(task);

        // 上报数据服务
        dataServiceClient.reportTaskCreated(task);

        return task;
    }

    @Override
    public List<Task> getTaskList() {
        return taskMapper.selectList(null);
    }

    @Override
    public List<Task> getTaskList(String status, String robotId) {
        QueryWrapper<Task> queryWrapper = new QueryWrapper<>();

        if (status != null && !status.isEmpty()) {
            queryWrapper.eq("status", status);
        }

        if (robotId != null && !robotId.isEmpty()) {
            queryWrapper.eq("robot_id", robotId);
        }

        // 按动态优先级分数排序
        queryWrapper.orderByAsc("dynamic_priority_score", "priority");

        return taskMapper.selectList(queryWrapper);
    }

    @Override
    public Task getTaskById(String taskId) {
        return taskMapper.selectById(taskId);
    }

    @Override
    @Transactional
    public boolean updateTaskStatus(String taskId, String status, String reason) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }

        String oldStatus = task.getStatus();
        task.setStatus(status);

        if (StatusConstant.TASK_STATUS_RUNNING.equals(status)) {
            task.setStartTime(new Date());
        } else if (StatusConstant.TASK_STATUS_COMPLETED.equals(status) || StatusConstant.TASK_STATUS_FAILED.equals(status)) {
            task.setFinishTime(new Date());
            if (StatusConstant.TASK_STATUS_FAILED.equals(status)) {
                task.setFailReason(reason);
            }

            // 任务完成/失败时记录日志
            String logMessage = "任务 " + taskId + " " + (
                    StatusConstant.TASK_STATUS_COMPLETED.equals(status) ? "完成" : "失败: " + reason
            );
            logService.createLog("TASK", logMessage, taskId);
        }

        boolean success = taskMapper.updateById(task) > 0;

        // 上报数据服务
        if (success) {
            dataServiceClient.reportTaskStatusChanged(taskId, oldStatus, status, reason);
        }

        return success;
    }

    @Override
    public boolean deleteTask(String taskId) {
        return taskMapper.deleteById(taskId) > 0;
    }

    @Override
    public List<Task> getPendingTasks() {
        QueryWrapper<Task> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", StatusConstant.TASK_STATUS_PENDING);
        queryWrapper.orderByAsc("dynamic_priority_score", "priority");
        return taskMapper.selectList(queryWrapper);
    }

    @Override
    @Transactional
    public boolean cancelTask(String taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null) {
            return false;
        }

        // 如果任务还在排队，直接从队列移除并删除
        if (StatusConstant.TASK_STATUS_PENDING.equals(task.getStatus())) {
            taskMapper.deleteById(taskId);
            scheduleService.refreshQueueFromDb();
            log.info("任务 {} 已取消并移除", taskId);
            return true;
        }

        // 如果任务正在执行，标记为失败
        if (StatusConstant.TASK_STATUS_RUNNING.equals(task.getStatus())) {
            String oldStatus = task.getStatus();
            task.setStatus(StatusConstant.TASK_STATUS_FAILED);
            task.setFinishTime(new Date());
            task.setFailReason("手动取消");
            boolean success = taskMapper.updateById(task) > 0;
            if (success) {
                logService.createLog("TASK", "任务 " + taskId + " 被手动取消", taskId);
                dataServiceClient.reportTaskStatusChanged(taskId, oldStatus, StatusConstant.TASK_STATUS_FAILED, "手动取消");
            }
            return success;
        }

        // 已完成或已失败，无需操作
        return false;
    }

    @Override
    @Transactional
    public boolean reassignTask(String taskId) {
        Task task = taskMapper.selectById(taskId);
        if (task == null || !StatusConstant.TASK_STATUS_RUNNING.equals(task.getStatus())) {
            return false;
        }

        String oldStatus = task.getStatus();
        String robotId = task.getRobotId();

        // 释放机器人
        if (robotId != null) {
            Robot robot = robotMapper.selectById(robotId);
            if (robot != null) {
                robot.setStatus(StatusConstant.ROBOT_STATUS_IDLE);
                robot.setLoad(Math.max(0, robot.getLoad() - 1));
                robotMapper.updateById(robot);
            }
        }

        // 任务回退到待执行
        task.setStatus(StatusConstant.TASK_STATUS_PENDING);
        task.setRobotId(null);
        task.setStartTime(null);
        taskMapper.updateById(task);

        // 记录变更
        dataServiceClient.reportTaskStatusChanged(taskId, oldStatus, StatusConstant.TASK_STATUS_PENDING, "手动重新分配");
        scheduleService.refreshQueueFromDb();
        scheduleService.triggerSchedule();

        log.info("任务 {} 已回退并触发重新调度", taskId);
        return true;
    }

    @Override
    @Transactional
    public boolean updateTaskPriority(String taskId, Integer priority) {
        Task task = taskMapper.selectById(taskId);
        if (task == null || priority == null) {
            return false;
        }

        task.setPriority(priority);
        taskMapper.updateById(task);

        // 如果任务在队列中，刷新队列顺序
        if (StatusConstant.TASK_STATUS_PENDING.equals(task.getStatus())) {
            scheduleService.refreshQueueFromDb();
        }

        log.info("任务 {} 优先级已调整为 {}", taskId, priority);
        return true;
    }
}
