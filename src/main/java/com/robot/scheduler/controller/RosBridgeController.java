package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.service.RosBridgeService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/ros")
public class RosBridgeController {

    @Autowired
    private RosBridgeService rosBridgeService;

    /**
     * 查询 rosbridge 连接状态
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        return Result.success(rosBridgeService.getConnectionStatus());
    }

    /**
     * 发送导航目标点到 ROS2
     *
     * @param body { "robotCode": "", "x": 0.0, "y": 0.0, "yaw": 0.0 }
     */
    @PostMapping("/goal")
    public Result<Map<String, Object>> sendGoal(@RequestBody Map<String, Object> body) {
        String robotCode = body.get("robotCode") != null ? String.valueOf(body.get("robotCode")) : "";
        double x = parseDouble(body.get("x"), 0.0);
        double y = parseDouble(body.get("y"), 0.0);
        double yaw = parseDouble(body.get("yaw"), 0.0);

        boolean sent = rosBridgeService.sendNavigationGoal(robotCode, x, y, yaw);
        if (sent) {
            return Result.success(Map.of("sent", true, "robotCode", robotCode, "x", x, "y", y, "yaw", yaw));
        } else {
            return Result.error("导航目标发送失败，请检查 rosbridge 连接状态");
        }
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
