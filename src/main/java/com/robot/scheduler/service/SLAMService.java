package com.robot.scheduler.service;

import java.util.List;
import java.util.Map;

public interface SLAMService {

    // ========== 地图管理 ==========

    /** 获取地图数据（OccupancyGrid 格式） */
    Map<String, Object> getMapData();

    /** 更新/上传地图数据 */
    Map<String, Object> updateMapData(Map<String, Object> mapData);

    /** 重置地图 */
    Map<String, Object> resetMap();

    /** 获取地图状态 */
    Map<String, Object> getMapStatus();

    // ========== 障碍物 / 空气墙 ==========

    /** 获取障碍物（含空气墙）列表 */
    List<Map<String, Object>> getObstacles();

    /** 添加障碍物或空气墙 */
    Map<String, Object> addObstacle(Map<String, Object> obstacleData);

    /** 删除障碍物 */
    Map<String, Object> removeObstacle(String obstacleId);

    /** 修改障碍物 */
    Map<String, Object> updateObstacle(String obstacleId, Map<String, Object> obstacleData);

    // ========== 路径规划 ==========

    /**
     * 在地图上手动标点规划路径（A*）
     *
     * @param startX 起点 X（米）
     * @param startY 起点 Y（米）
     * @param goalX  终点 X（米）
     * @param goalY  终点 Y（米）
     * @return 路径点数组 [{x, y}, ...]
     */
    List<Map<String, Object>> planPath(double startX, double startY, double goalX, double goalY);
}
