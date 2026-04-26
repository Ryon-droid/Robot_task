package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.service.SLAMService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/slam")
public class SLAMController {

    @Autowired
    private SLAMService slamService;

    // ==================== 地图管理 ====================

    @GetMapping("/map")
    public Result<Map<String, Object>> getMapData() {
        return Result.success(slamService.getMapData());
    }

    @PostMapping("/map")
    public Result<Map<String, Object>> updateMapData(@RequestBody Map<String, Object> mapData) {
        return Result.success(slamService.updateMapData(mapData));
    }

    @PostMapping("/map/reset")
    public Result<Map<String, Object>> resetMap() {
        return Result.success(slamService.resetMap());
    }

    @GetMapping("/map/status")
    public Result<Map<String, Object>> getMapStatus() {
        return Result.success(slamService.getMapStatus());
    }

    // ==================== 障碍物 / 空气墙 ====================

    @GetMapping("/obstacles")
    public Result<List<Map<String, Object>>> getObstacles() {
        return Result.success(slamService.getObstacles());
    }

    @PostMapping("/obstacles")
    public Result<Map<String, Object>> addObstacle(@RequestBody Map<String, Object> obstacleData) {
        return Result.success(slamService.addObstacle(obstacleData));
    }

    @PutMapping("/obstacles/{obstacleId}")
    public Result<Map<String, Object>> updateObstacle(
            @PathVariable String obstacleId,
            @RequestBody Map<String, Object> obstacleData) {
        return Result.success(slamService.updateObstacle(obstacleId, obstacleData));
    }

    @DeleteMapping("/obstacles/{obstacleId}")
    public Result<Map<String, Object>> removeObstacle(@PathVariable String obstacleId) {
        return Result.success(slamService.removeObstacle(obstacleId));
    }

    // ==================== 路径规划 ====================

    @PostMapping("/path/plan")
    public Result<List<Map<String, Object>>> planPath(@RequestBody Map<String, Object> request) {
        double startX = parseDouble(request.get("startX"));
        double startY = parseDouble(request.get("startY"));
        double goalX = parseDouble(request.get("goalX"));
        double goalY = parseDouble(request.get("goalY"));
        return Result.success(slamService.planPath(startX, startY, goalX, goalY));
    }

    private double parseDouble(Object value) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value != null ? Double.parseDouble(String.valueOf(value)) : 0.0;
    }
}
