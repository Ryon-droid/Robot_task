package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.service.SLAMService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/slam")
public class SLAMController {
    
    @Autowired
    private SLAMService slamService;
    
    // 获取激光地图数据
    @GetMapping("/map/data")
    public Result<Map<String, Object>> getMapData() {
        Map<String, Object> data = slamService.getMapData();
        return Result.success(data);
    }
    
    // 更新地图数据
    @PostMapping("/map/update")
    public Result<Map<String, Object>> updateMapData(@RequestBody Map<String, Object> mapData) {
        Map<String, Object> result = slamService.updateMapData(mapData);
        return Result.success(result);
    }
    
    // 重置地图
    @PostMapping("/map/reset")
    public Result<Map<String, Object>> resetMap() {
        Map<String, Object> result = slamService.resetMap();
        return Result.success(result);
    }
    
    // 获取地图状态
    @GetMapping("/map/status")
    public Result<Map<String, Object>> getMapStatus() {
        Map<String, Object> status = slamService.getMapStatus();
        return Result.success(status);
    }
    
    // 添加障碍物（如玻璃墙）
    @PostMapping("/obstacle/add")
    public Result<Map<String, Object>> addObstacle(@RequestBody Map<String, Object> obstacleData) {
        Map<String, Object> result = slamService.addObstacle(obstacleData);
        return Result.success(result);
    }
    
    // 删除障碍物
    @DeleteMapping("/obstacle/remove/{obstacleId}")
    public Result<Map<String, Object>> removeObstacle(@PathVariable String obstacleId) {
        Map<String, Object> result = slamService.removeObstacle(obstacleId);
        return Result.success(result);
    }
    
    // 修改障碍物
    @PutMapping("/obstacle/update/{obstacleId}")
    public Result<Map<String, Object>> updateObstacle(@PathVariable String obstacleId, @RequestBody Map<String, Object> obstacleData) {
        Map<String, Object> result = slamService.updateObstacle(obstacleId, obstacleData);
        return Result.success(result);
    }
}