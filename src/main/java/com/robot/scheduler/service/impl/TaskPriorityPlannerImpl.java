package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.mapper.TaskMapper;
import com.robot.scheduler.service.TaskPriorityPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class TaskPriorityPlannerImpl implements TaskPriorityPlanner {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private RobotMapper robotMapper;

    @Autowired
    private ObjectMapper objectMapper;

    @Value("${scheduler.priority.weight.base:1.0}")
    private double weightBase;

    @Value("${scheduler.priority.weight.waiting:1.0}")
    private double weightWaiting;

    @Value("${scheduler.priority.weight.deadline:1.0}")
    private double weightDeadline;

    @Value("${scheduler.priority.weight.type:1.0}")
    private double weightType;

    @Value("${scheduler.priority.weight.robot:1.0}")
    private double weightRobot;

    // 任务类型默认权重（分数越低表示该类型在调度中越优先）
    private static final Map<String, Double> DEFAULT_TYPE_WEIGHTS = Map.ofEntries(
            Map.entry("NAVIGATE", 10.0),
            Map.entry("GRAB", 15.0),
            Map.entry("LLM_PLAN", 20.0),
            Map.entry("CHARGE", 5.0),
            Map.entry("INSPECT", 12.0),
            Map.entry("DEFAULT", 10.0)
    );

    /**
     * 计算单条任务的动态优先级分数（越低越优先）
     */
    public double calculateScore(Task task, List<Robot> robots) {
        // 1. 基础优先级分：priority 1~5 → 10~50
        double baseScore = (task.getPriority() != null ? task.getPriority() : 3) * 10.0;

        // 2. 等待时间分：每等待1分钟加1分，封顶30分
        long now = System.currentTimeMillis();
        long createTime = task.getCreateTime() != null ? task.getCreateTime().getTime() : now;
        long waitingMs = Math.max(0, now - createTime);
        double waitingScore = Math.min(waitingMs / 60000.0, 30.0);

        // 3. 截止时间紧急度
        double deadlineScore = 0.0;
        if (task.getDeadline() != null) {
            long deadlineMs = task.getDeadline().getTime();
            long timeToDeadline = deadlineMs - now;
            if (timeToDeadline <= 0) {
                deadlineScore = 100.0; // 已过期，最高紧急
            } else {
                // 距离截止≤1h：从50线性递减到0；>1h：0
                deadlineScore = Math.max(0.0, 50.0 - timeToDeadline / 3600000.0 * 50.0);
            }
        }

        // 4. 任务类型权重
        String cmdType = task.getCommandType() != null ? task.getCommandType().toUpperCase() : "DEFAULT";
        double typeScore = DEFAULT_TYPE_WEIGHTS.getOrDefault(cmdType, DEFAULT_TYPE_WEIGHTS.get("DEFAULT"));

        // 5. 最佳机器人匹配度
        double robotMatchScore = calculateBestRobotMatch(task, robots);

        double total = weightBase * baseScore
                + weightWaiting * waitingScore
                + weightDeadline * deadlineScore
                + weightType * typeScore
                + weightRobot * robotMatchScore;

        log.debug("任务 {} 优先级评分: base={}, waiting={}, deadline={}, type={}, robot={}, total={}",
                task.getTaskId(), baseScore, waitingScore, deadlineScore, typeScore, robotMatchScore, total);

        return total;
    }

    /**
     * 计算任务与最佳空闲机器人的匹配分数（越低越好）
     */
    private double calculateBestRobotMatch(Task task, List<Robot> robots) {
        if (robots == null || robots.isEmpty()) {
            return 50.0;
        }

        // 尝试从 taskParams JSON 中解析目标坐标
        Double targetX = null;
        Double targetY = null;
        if (task.getTaskParams() != null && !task.getTaskParams().isEmpty()) {
            try {
                JsonNode node = objectMapper.readTree(task.getTaskParams());
                if (node.has("targetX")) targetX = node.get("targetX").asDouble();
                if (node.has("targetY")) targetY = node.get("targetY").asDouble();
                if (node.has("x") && targetX == null) targetX = node.get("x").asDouble();
                if (node.has("y") && targetY == null) targetY = node.get("y").asDouble();
            } catch (Exception e) {
                log.debug("解析任务 {} 参数失败: {}", task.getTaskId(), e.getMessage());
            }
        }

        if (targetX == null || targetY == null) {
            return 50.0; // 无目标坐标，取默认中等分数
        }

        double bestScore = Double.MAX_VALUE;
        int validRobotCount = 0;
        for (Robot robot : robots) {
            if (robot.getX() == null || robot.getY() == null) {
                continue;
            }
            validRobotCount++;
            double dx = robot.getX() - targetX;
            double dy = robot.getY() - targetY;
            double distance = Math.sqrt(dx * dx + dy * dy);

            int battery = robot.getBattery() != null ? robot.getBattery() : 50;
            // 电量越低，惩罚越高（每缺10%电量加1分）
            double batteryPenalty = (100.0 - battery) / 10.0;

            double score = distance + batteryPenalty;
            if (score < bestScore) {
                bestScore = score;
            }
        }

        if (validRobotCount == 0) {
            return 50.0;
        }
        return bestScore;
    }

    @Override
    public int recalculateAllPendingTasks() {
        QueryWrapper<Task> wrapper = new QueryWrapper<>();
        wrapper.eq("status", StatusConstant.TASK_STATUS_PENDING);
        List<Task> tasks = taskMapper.selectList(wrapper);
        if (tasks == null || tasks.isEmpty()) {
            return 0;
        }

        List<Robot> robots = robotMapper.selectList(null);

        int count = 0;
        for (Task task : tasks) {
            try {
                double score = calculateScore(task, robots);
                task.setDynamicPriorityScore(score);
                taskMapper.updateById(task);
                count++;
            } catch (Exception e) {
                log.warn("计算任务 {} 动态优先级失败: {}", task.getTaskId(), e.getMessage());
            }
        }

        log.info("动态优先级重算完成，共更新 {} / {} 条任务", count, tasks.size());
        return count;
    }
}
