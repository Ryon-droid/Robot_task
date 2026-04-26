package com.robot.scheduler.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.common.Result;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.RobotService;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 面向数据服务（S-17）的外部接口
 * 路径前缀：/scheduler
 */
@RestController
@RequestMapping("/scheduler")
public class SchedulerExternalController {

    @Autowired
    private RobotService robotService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 机器人查询接口 ====================

    @GetMapping("/robots")
    public Result<List<Map<String, Object>>> getRobots() {
        List<Robot> robots = robotService.getRobotList();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Robot robot : robots) {
            result.add(buildRobotResponse(robot));
        }
        return Result.success(result);
    }

    @GetMapping("/robots/{robotId}")
    public Result<Map<String, Object>> getRobotById(@PathVariable String robotId) {
        Robot robot = robotService.getRobotById(robotId);
        if (robot == null) {
            return Result.error("机器人不存在");
        }
        return Result.success(buildRobotResponse(robot));
    }

    // ==================== 任务查询接口 ====================

    @GetMapping("/tasks")
    public Result<List<Map<String, Object>>> getTasks(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String robotId) {

        List<Task> tasks = taskService.getTaskList(status, robotId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task task : tasks) {
            result.add(buildTaskResponse(task));
        }
        return Result.success(result);
    }

    @GetMapping("/tasks/{taskId}")
    public Result<Map<String, Object>> getTaskById(@PathVariable String taskId) {
        Task task = taskService.getTaskById(taskId);
        if (task == null) {
            return Result.error("任务不存在");
        }
        return Result.success(buildTaskResponse(task));
    }

    @GetMapping("/tasks/queue")
    public Result<List<Map<String, Object>>> getTaskQueue() {
        List<Task> queue = scheduleService.getPendingQueue();
        List<Map<String, Object>> result = new ArrayList<>();
        for (Task task : queue) {
            result.add(buildTaskResponse(task));
        }
        return Result.success(result);
    }

    // ==================== 任务控制接口 ====================

    @PostMapping("/tasks/{taskId}/cancel")
    public Result<Map<String, Object>> cancelTask(@PathVariable String taskId) {
        boolean success = taskService.cancelTask(taskId);
        if (!success) {
            return Result.error("取消失败，任务不存在或已完成");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", taskId);
        data.put("action", "cancel");
        return Result.success(data);
    }

    @PostMapping("/tasks/{taskId}/reassign")
    public Result<Map<String, Object>> reassignTask(@PathVariable String taskId) {
        boolean success = taskService.reassignTask(taskId);
        if (!success) {
            return Result.error("重新分配失败，任务不存在或未在执行中");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", taskId);
        data.put("action", "reassign");
        return Result.success(data);
    }

    @PostMapping("/tasks/{taskId}/priority")
    public Result<Map<String, Object>> updateTaskPriority(
            @PathVariable String taskId,
            @RequestBody Map<String, Object> request) {

        Integer priority = (Integer) request.get("priority");
        if (priority == null) {
            return Result.error("优先级参数不能为空");
        }
        boolean success = taskService.updateTaskPriority(taskId, priority);
        if (!success) {
            return Result.error("调整优先级失败");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("taskId", taskId);
        data.put("priority", priority);
        return Result.success(data);
    }

    // ==================== 机器人控制接口 ====================

    @PostMapping("/robots/{robotId}/emergency_stop")
    public Result<Map<String, Object>> emergencyStop(@PathVariable String robotId) {
        boolean success = robotService.emergencyStop(robotId);
        if (!success) {
            return Result.error("紧急停止失败，机器人不存在");
        }
        Map<String, Object> data = new HashMap<>();
        data.put("robotId", robotId);
        data.put("action", "emergency_stop");
        return Result.success(data);
    }

    // ==================== 响应组装工具 ====================

    private Map<String, Object> buildRobotResponse(Robot robot) {
        Map<String, Object> map = new HashMap<>();
        map.put("robotId", robot.getRobotId());
        map.put("robotName", robot.getRobotName());
        map.put("robotCode", robot.getRobotCode());
        map.put("status", robot.getStatus());
        map.put("load", robot.getLoad());
        map.put("battery", robot.getBattery());
        map.put("x", robot.getX());
        map.put("y", robot.getY());
        map.put("yaw", robot.getYaw());
        map.put("lastHeartbeat", robot.getLastHeartbeat() != null ? robot.getLastHeartbeat().getTime() : null);
        return map;
    }

    private Map<String, Object> buildTaskResponse(Task task) {
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", task.getTaskId());
        map.put("taskName", task.getTaskName());
        map.put("commandType", task.getCommandType());
        map.put("priority", task.getPriority());
        map.put("robotId", task.getRobotId());
        map.put("robotCode", task.getRobotCode());
        map.put("status", task.getStatus());
        map.put("params", parseParams(task.getTaskParams()));
        map.put("createTime", task.getCreateTime() != null ? task.getCreateTime().getTime() : null);
        map.put("startTime", task.getStartTime() != null ? task.getStartTime().getTime() : null);
        map.put("finishTime", task.getFinishTime() != null ? task.getFinishTime().getTime() : null);
        map.put("failReason", task.getFailReason());
        map.put("deadline", task.getDeadline() != null ? task.getDeadline().getTime() : null);
        map.put("estimatedDuration", task.getEstimatedDuration());
        map.put("dynamicPriorityScore", task.getDynamicPriorityScore());
        return map;
    }

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
