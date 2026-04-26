package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.LLMService;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/llm")
public class LLMController {

    @Autowired
    private LLMService llmService;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduleService scheduleService;

    /**
     * 自然语言指令解析
     * POST /api/v1/scheduler/llm/parse
     */
    @PostMapping("/parse")
    public Result<Map<String, Object>> parseNaturalLanguage(@RequestBody Map<String, Object> request) {
        String instruction = (String) request.get("instruction");

        // 调用 LLM 解析，返回子任务列表
        List<Task> tasks = llmService.parseNaturalLanguage(instruction);

        // 逐个入库
        List<Map<String, Object>> taskResponses = new ArrayList<>();
        for (Task task : tasks) {
            taskService.createTask(task);
            taskResponses.add(buildTaskResponse(task));
        }

        // 触发调度
        scheduleService.triggerSchedule();

        Map<String, Object> response = new HashMap<>();
        response.put("taskCount", taskResponses.size());
        response.put("tasks", taskResponses);
        return Result.success(response);
    }

    private Map<String, Object> buildTaskResponse(Task task) {
        Map<String, Object> map = new HashMap<>();
        map.put("taskId", task.getTaskId());
        map.put("taskName", task.getTaskName());
        map.put("commandType", task.getCommandType());
        map.put("priority", task.getPriority());
        map.put("status", task.getStatus());
        map.put("robotCode", task.getRobotCode());
        map.put("createdAt", task.getCreateTime() != null ? task.getCreateTime().getTime() : null);
        return map;
    }
}
