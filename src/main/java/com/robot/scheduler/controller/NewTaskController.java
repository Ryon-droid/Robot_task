package com.robot.scheduler.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.common.Result;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.TaskService;
import com.robot.scheduler.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.Date;
import java.util.HashMap;
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
    private ObjectMapper objectMapper;

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
        task.setStatus(StatusConstant.TASK_STATUS_PENDING);
        task.setTaskParams(serializeParams(params));
        task.setTaskName(commandType + "任务");

        // 保存任务
        Task createdTask = taskService.createTask(task);

        // 触发调度
        scheduleService.triggerSchedule();

        return Result.success(buildTaskResponse(createdTask));
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
            response.add(buildTaskResponse(task));
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
        return Result.success(buildTaskResponse(task));
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
            Map<String, Object> response = new HashMap<>();
            response.put("taskId", task.getTaskId());
            response.put("status", task.getStatus());
            return Result.success(response);
        } else {
            return Result.error("更新状态失败");
        }
    }

    /**
     * 将 Task 实体组装为统一响应 Map
     */
    private Map<String, Object> buildTaskResponse(Task task) {
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", task.getTaskId());
        map.put("robotId", task.getRobotId());
        map.put("robotCode", task.getRobotCode());
        map.put("commandType", task.getCommandType());
        map.put("priority", task.getPriority());
        map.put("status", task.getStatus());
        map.put("createdAt", task.getCreateTime() != null ? task.getCreateTime().getTime() : null);
        map.put("params", parseParams(task.getTaskParams()));
        return map;
    }

    /**
     * 将请求参数 Map 序列化为 JSON 字符串
     */
    private String serializeParams(Map<String, Object> params) {
        if (params == null) {
            return "{}";
        }
        try {
            return objectMapper.writeValueAsString(params);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * 将 taskParams JSON 字符串解析为 Map
     */
    private Map<String, Object> parseParams(String taskParams) {
        if (taskParams == null || taskParams.isEmpty()) {
            return new HashMap<>();
        }
        try {
            return objectMapper.readValue(taskParams, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return new HashMap<>();
        }
    }
}
