package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.LLMService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/llm")
public class LLMController {

    @Autowired
    private LLMService llmService;

    // 自然语言指令解析
    @PostMapping("/parse")
    public Result<Task> parseInstruction(@RequestBody Map<String, String> instruction) {
        String naturalLanguage = instruction.get("instruction");
        Task task = llmService.parseNaturalLanguage(naturalLanguage);
        return Result.success(task);
    }

    // 任务组合与切换
    @PostMapping("/task/combine")
    public Result<Map<String, Object>> combineTasks(@RequestBody Map<String, Object> taskData) {
        Map<String, Object> result = llmService.combineTasks(taskData);
        return Result.success(result);
    }

    // 获取行为树状态
    @GetMapping("/behavior/status")
    public Result<Map<String, Object>> getBehaviorStatus() {
        Map<String, Object> status = llmService.getBehaviorTreeStatus();
        return Result.success(status);
    }

    // 行为树节点执行
    @PostMapping("/behavior/execute")
    public Result<Map<String, Object>> executeBehavior(@RequestBody Map<String, Object> behaviorData) {
        Map<String, Object> result = llmService.executeBehaviorNode(behaviorData);
        return Result.success(result);
    }
}
