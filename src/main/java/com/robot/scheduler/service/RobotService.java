package com.robot.scheduler.service;

import com.robot.scheduler.entity.Robot;

import java.util.List;
import java.util.Map;

public interface RobotService {
    
    /**
     * 获取机器人列表
     */
    List<Robot> getRobotList();
    
    /**
     * 设置机器人目标点
     */
    boolean setRobotGoal(String robotId, Double x, Double y, Double yaw);
    
    /**
     * 获取机器人规划路径
     */
    List<Map<String, Object>> getRobotPath(String robotId);
    
    /**
     * 更新机器人位置
     */
    void updateRobotPose(String robotId, Double x, Double y, Double yaw);
    
    /**
     * 根据ID获取机器人
     */
    Robot getRobotById(String robotId);

    /**
     * 紧急停止机器人
     */
    boolean emergencyStop(String robotId);
}