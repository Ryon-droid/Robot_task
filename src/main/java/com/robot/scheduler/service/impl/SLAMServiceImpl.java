package com.robot.scheduler.service.impl;

import com.robot.scheduler.service.SLAMService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class SLAMServiceImpl implements SLAMService {
    
    // 模拟地图数据存储
    private Map<String, Object> mapData = new HashMap<>();
    private boolean isMapping = false;
    
    // 障碍物存储
    private List<Map<String, Object>> obstacles = new ArrayList<>();
    
    @Override
    public Map<String, Object> getMapData() {
        // 确保障碍物数据包含在地图数据中
        mapData.put("obstacles", obstacles);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "获取地图数据成功");
        result.put("mapData", mapData);
        return result;
    }
    
    @Override
    public Map<String, Object> updateMapData(Map<String, Object> mapData) {
        this.mapData.putAll(mapData);
        // 如果包含障碍物数据，更新障碍物列表
        if (mapData.containsKey("obstacles")) {
            obstacles = (List<Map<String, Object>>) mapData.get("obstacles");
        }
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "更新地图数据成功");
        return result;
    }
    
    @Override
    public Map<String, Object> resetMap() {
        mapData.clear();
        obstacles.clear();
        isMapping = false;
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "重置地图成功");
        return result;
    }
    
    @Override
    public Map<String, Object> getMapStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "获取地图状态成功");
        result.put("isMapping", isMapping);
        result.put("mapSize", mapData.size());
        result.put("obstacleCount", obstacles.size());
        return result;
    }
    
    @Override
    public Map<String, Object> addObstacle(Map<String, Object> obstacleData) {
        // 生成唯一ID
        String obstacleId = UUID.randomUUID().toString();
        obstacleData.put("id", obstacleId);
        obstacles.add(obstacleData);
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "添加障碍物成功");
        result.put("obstacleId", obstacleId);
        return result;
    }
    
    @Override
    public Map<String, Object> removeObstacle(String obstacleId) {
        boolean removed = obstacles.removeIf(obstacle -> obstacleId.equals(obstacle.get("id")));
        
        Map<String, Object> result = new HashMap<>();
        if (removed) {
            result.put("status", "success");
            result.put("message", "删除障碍物成功");
        } else {
            result.put("status", "error");
            result.put("message", "障碍物不存在");
        }
        return result;
    }
    
    @Override
    public Map<String, Object> updateObstacle(String obstacleId, Map<String, Object> obstacleData) {
        for (Map<String, Object> obstacle : obstacles) {
            if (obstacleId.equals(obstacle.get("id"))) {
                // 保留ID，更新其他属性
                obstacleData.put("id", obstacleId);
                obstacle.clear();
                obstacle.putAll(obstacleData);
                
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("message", "修改障碍物成功");
                return result;
            }
        }
        
        Map<String, Object> result = new HashMap<>();
        result.put("status", "error");
        result.put("message", "障碍物不存在");
        return result;
    }
}