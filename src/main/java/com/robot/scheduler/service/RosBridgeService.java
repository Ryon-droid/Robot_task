package com.robot.scheduler.service;

import java.util.Map;

public interface RosBridgeService {

    /**
     * 发送导航目标点到 ROS2（通过 /goal_pose）
     *
     * @param robotCode 机器人编码
     * @param x         目标 X（米）
     * @param y         目标 Y（米）
     * @param yaw       目标朝向（弧度）
     * @return 是否发送成功
     */
    boolean sendNavigationGoal(String robotCode, double x, double y, double yaw);

    /**
     * 获取 rosbridge 连接状态与统计信息
     */
    Map<String, Object> getConnectionStatus();
}
