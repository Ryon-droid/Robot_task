package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.service.RobotService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
public class RobotServiceImpl implements RobotService {

    @Autowired
    private RobotMapper robotMapper;

    // 存储目标点
    private Map<String, Map<String, Object>> robotGoals = new HashMap<>();
    
    // 存储规划路径
    private Map<String, List<Map<String, Object>>> robotPaths = new HashMap<>();

    @Override
    public List<Robot> getRobotList() {
        return robotMapper.selectList(null);
    }

    @Override
    public boolean setRobotGoal(String robotId, Double x, Double y, Double yaw) {
        Robot robot = robotMapper.selectById(robotId);
        if (robot == null) {
            return false;
        }
        
        Map<String, Object> goal = new HashMap<>();
        goal.put("x", x);
        goal.put("y", y);
        goal.put("yaw", yaw);
        goal.put("timestamp", System.currentTimeMillis());
        robotGoals.put(robotId, goal);
        
        // 生成简单直线路径（实际应由路径规划算法生成）
        generatePath(robotId, robot.getX(), robot.getY(), x, y);
        
        return true;
    }

    @Override
    public List<Map<String, Object>> getRobotPath(String robotId) {
        return robotPaths.getOrDefault(robotId, new ArrayList<>());
    }

    @Override
    public void updateRobotPose(String robotId, Double x, Double y, Double yaw) {
        Robot robot = robotMapper.selectById(robotId);
        if (robot != null) {
            robot.setX(x);
            robot.setY(y);
            robot.setYaw(yaw);
            robotMapper.updateById(robot);
        }
    }

    @Override
    public Robot getRobotById(String robotId) {
        return robotMapper.selectById(robotId);
    }
    
    /**
     * 生成直线路径（简化版，实际应使用A*等算法）
     */
    private void generatePath(String robotId, Double startX, Double startY, Double goalX, Double goalY) {
        List<Map<String, Object>> path = new ArrayList<>();
        
        // 如果起点或终点为空，返回空路径
        if (startX == null || startY == null) {
            robotPaths.put(robotId, path);
            return;
        }
        
        // 生成直线路径点（每0.1米一个点）
        double distance = Math.sqrt(Math.pow(goalX - startX, 2) + Math.pow(goalY - startY, 2));
        int steps = Math.max((int)(distance / 0.1), 1);
        
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            Map<String, Object> point = new HashMap<>();
            point.put("x", startX + (goalX - startX) * t);
            point.put("y", startY + (goalY - startY) * t);
            path.add(point);
        }
        
        robotPaths.put(robotId, path);
    }
}