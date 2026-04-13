package com.robot.scheduler.service;

import com.robot.scheduler.entity.Task;

import java.util.Map;

public interface LLMService {
    // 解析自然语言指令
    Task parseNaturalLanguage(String instruction);

    // 任务组合
    Map<String, Object> combineTasks(Map<String, Object> taskData);

    // 获取行为树状态
    Map<String, Object> getBehaviorTreeStatus();

    // 执行行为树节点
    Map<String, Object> executeBehaviorNode(Map<String, Object> behaviorData);
}
