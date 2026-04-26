package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.service.RobotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class RobotController {

    @Autowired
    private RobotService robotService;

    /**
     * 获取机器人列表
     * GET /api/robots
     * 返回：id, name, x, y, status
     */
    @GetMapping("/robots")
    public Result<List<Map<String, Object>>> getRobots() {
        List<Robot> robots = robotService.getRobotList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Robot robot : robots) {
            Map<String, Object> robotInfo = new HashMap<>();
            robotInfo.put("id", robot.getRobotId());
            robotInfo.put("name", robot.getRobotName());
            robotInfo.put("x", robot.getX() != null ? robot.getX() : 0.0);
            robotInfo.put("y", robot.getY() != null ? robot.getY() : 0.0);
            robotInfo.put("status", robot.getStatus());
            result.add(robotInfo);
        }

        return Result.success(result);
    }

    /**
     * 实时位置更新
     * GET /api/robots/pose
     * 返回：id, x, y, yaw
     */
    @GetMapping("/robots/pose")
    public Result<List<Map<String, Object>>> getRobotsPose() {
        List<Robot> robots = robotService.getRobotList();
        List<Map<String, Object>> result = new ArrayList<>();

        for (Robot robot : robots) {
            Map<String, Object> pose = new HashMap<>();
            pose.put("id", robot.getRobotId());
            pose.put("x", robot.getX() != null ? robot.getX() : 0.0);
            pose.put("y", robot.getY() != null ? robot.getY() : 0.0);
            pose.put("yaw", robot.getYaw() != null ? robot.getYaw() : 0.0);
            result.add(pose);
        }

        return Result.success(result);
    }

    /**
     * 设置机器人目标点
     * POST /api/robot/goal
     * body: { robotId, x, y, yaw }
     */
    @PostMapping("/robot/goal")
    public Result<Map<String, Object>> setRobotGoal(@RequestBody Map<String, Object> goalData) {
        String robotId = (String) goalData.get("robotId");
        Double x = ((Number) goalData.get("x")).doubleValue();
        Double y = ((Number) goalData.get("y")).doubleValue();
        Double yaw = goalData.get("yaw") != null ? ((Number) goalData.get("yaw")).doubleValue() : 0.0;

        boolean success = robotService.setRobotGoal(robotId, x, y, yaw);

        Map<String, Object> result = new HashMap<>();
        if (success) {
            result.put("status", "success");
            result.put("message", "目标点设置成功");
            result.put("robotId", robotId);
            result.put("goal", Map.of("x", x, "y", y, "yaw", yaw));
        } else {
            result.put("status", "error");
            result.put("message", "机器人不存在或设置失败");
        }

        return Result.success(result);
    }

    /**
     * 获取规划路径
     * GET /api/robot/path?robotId=xxx
     * 返回 path 数组
     */
    @GetMapping("/robot/path")
    public Result<Map<String, Object>> getRobotPath(@RequestParam String robotId) {
        List<Map<String, Object>> path = robotService.getRobotPath(robotId);

        Map<String, Object> result = new HashMap<>();
        result.put("robotId", robotId);
        result.put("path", path != null ? path : new ArrayList<>());

        return Result.success(result);
    }
}
