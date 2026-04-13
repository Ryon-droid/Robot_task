package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.mapper.TaskMapper;
import com.robot.scheduler.service.TaskService;
import com.robot.scheduler.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Service
public class TaskServiceImpl implements TaskService {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private LogService logService;

    @Override
    @Transactional
    public Task createTask(Task task) {
        if (task.getTaskId() == null) {
            task.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        }
        task.setCreateTime(new Date());
        task.setStatus("QUEUED");  // 默认为队列状态
        taskMapper.insert(task);
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
        
        // 按优先级排序
        queryWrapper.orderByAsc("priority");
        
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
        
        task.setStatus(status);
        
        if ("RUNNING".equals(status)) {
            task.setStartTime(new Date());
        } else if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
            task.setFinishTime(new Date());
            if ("FAILED".equals(status)) {
                task.setFailReason(reason);
            }
            
            // 任务完成/失败时记录日志
            String logMessage = "任务 " + taskId + " " + (
                "SUCCESS".equals(status) ? "完成" : "失败: " + reason
            );
            logService.createLog("TASK", logMessage, taskId);
        }
        
        return taskMapper.updateById(task) > 0;
    }

    @Override
    public boolean deleteTask(String taskId) {
        return taskMapper.deleteById(taskId) > 0;
    }

    @Override
    public List<Task> getPendingTasks() {
        QueryWrapper<Task> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("status", "QUEUED");
        queryWrapper.orderByAsc("priority");
        return taskMapper.selectList(queryWrapper);
    }
}