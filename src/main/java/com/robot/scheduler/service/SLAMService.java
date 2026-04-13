package com.robot.scheduler.service;

import java.util.Map;

public interface SLAMService {
    // 获取激光地图数据
    Map<String, Object> getMapData();
    
    // 更新地图数据
    Map<String, Object> updateMapData(Map<String, Object> mapData);
    
    // 重置地图
    Map<String, Object> resetMap();
    
    // 获取地图状态
    Map<String, Object> getMapStatus();
    
    // 添加障碍物（如玻璃墙）
    Map<String, Object> addObstacle(Map<String, Object> obstacleData);
    
    // 删除障碍物
    Map<String, Object> removeObstacle(String obstacleId);
    
    // 修改障碍物
    Map<String, Object> updateObstacle(String obstacleId, Map<String, Object> obstacleData);
}