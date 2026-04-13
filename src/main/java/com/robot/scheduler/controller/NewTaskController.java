package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.TaskService;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/tasks")
public class NewTaskController {

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private LogService logService;

    /**
     * 创建任务
     * POST /api/v1/tasks
     */
    @PostMapping
    public Result<Map<String, Object>> createTask(@RequestBody Map<String, Object> request) {
        String robotId = (String) request.get("robotId");
        String commandType = (String) request.get("commandType");
        Integer priority = (Integer) request.get("priority");
        Map<String, Object> params = (Map<String, Object>) request.get("params");

        // 构建任务
        Task task = new Task();
        task.setRobotId(robotId);
        task.setCommandType(commandType);
        task.setPriority(priority != null ? priority : 3);  // 默认优先级3
        task.setStatus("QUEUED");
        task.setTaskParams(params != null ? params.toString() : "{}");
        task.setTaskName(commandType + "任务");

        // 保存任务
        Task createdTask = taskService.createTask(task);

        // 触发调度
        scheduleService.triggerSchedule();

        // 构建响应
        Map<String, Object> response = Map.of(
            "taskId", createdTask.getTaskId(),
            "robotId", createdTask.getRobotId(),
            "robotCode", createdTask.getRobotCode(),
            "commandType", createdTask.getCommandType(),
            "priority", createdTask.getPriority(),
            "status", createdTask.getStatus(),
            "createdAt", createdTask.getCreateTime().getTime(),
            "params", params
        );

        return Result.success(response);
    }

    /**
     * 获取任务列表
     * GET /api/v1/tasks?status=RUNNING&robotId=xxx
     */
    @GetMapping
    public Result<List<Map<String, Object>>> getTaskList(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String robotId) {
        
        List<Task> tasks = taskService.getTaskList(status, robotId);
        List<Map<String, Object>> response = new java.util.ArrayList<>();

        for (Task task : tasks) {
            Map<String, Object> taskMap = Map.of(
                "taskId", task.getTaskId(),
                "robotId", task.getRobotId(),
                "robotCode", task.getRobotCode(),
                "commandType", task.getCommandType(),
                "priority", task.getPriority(),
                "status", task.getStatus(),
                "createdAt", task.getCreateTime().getTime(),
                "params", task.getTaskParams()
            );
            response.add(taskMap);
        }

        return Result.success(response);
    }

    /**
     * 获取任务详情
     * GET /api/v1/tasks/{taskId}
     */
    @GetMapping("/{taskId}")
    public Result<Map<String, Object>> getTaskDetail(@PathVariable String taskId) {
        Task task = taskService.getTaskById(taskId);
        
        Map<String, Object> response = Map.of(
            "taskId", task.getTaskId(),
            "robotId", task.getRobotId(),
            "robotCode", task.getRobotCode(),
            "commandType", task.getCommandType(),
            "priority", task.getPriority(),
            "status", task.getStatus(),
            "createdAt", task.getCreateTime().getTime(),
            "params", task.getTaskParams()
        );

        return Result.success(response);
    }

    /**
     * 更新任务状态
     * PATCH /api/v1/tasks/{taskId}/status
     */
    @PatchMapping("/{taskId}/status")
    public Result<Map<String, Object>> updateTaskStatus(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {
        
        String status = (String) request.get("status");
        String reason = (String) request.get("reason");

        // 更新任务状态
        boolean success = taskService.updateTaskStatus(taskId, status, reason);

        if (success) {
            Task task = taskService.getTaskById(taskId);
            
            // 任务完成/失败时记录日志
            if ("SUCCESS".equals(status) || "FAILED".equals(status)) {
                String logMessage = "任务 " + taskId + " " + (
                    "SUCCESS".equals(status) ? "完成" : "失败: " + reason
                );
                logService.createLog("TASK", logMessage, taskId);
            }

            Map<String, Object> response = Map.of(
                "taskId", task.getTaskId(),
                "status", task.getStatus()
            );

            return Result.success(response);
        } else {
            return Result.error("更新状态失败");
        }
    }
}