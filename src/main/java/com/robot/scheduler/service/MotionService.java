package com.robot.scheduler.service;

import com.robot.scheduler.dto.ForwardKinematicsResult;
import com.robot.scheduler.dto.InverseKinematicsResult;

import java.util.Map;

/**
 * 视觉-运动学算法 WebSocket 通信服务
 * <p>
 * 后端作为 WebSocket 客户端，连接外部运动学算法服务端。
 * 上行：发送目标物体三维坐标；下行：接收正运动学位姿 + 逆运动学求解结果。
 */
public interface MotionService {

    /**
     * 发送目标物体信息给运动学算法端
     *
     * @param objName 目标物品名称
     * @param x       世界坐标 X（m）
     * @param y       世界坐标 Y（m）
     * @param z       世界坐标 Z（m）
     * @return 是否发送成功
     */
    boolean sendTargetObject(String objName, double x, double y, double z);

    /**
     * 获取 WebSocket 连接状态
     *
     * @return 连接状态信息
     */
    Map<String, Object> getConnectionStatus();

    /**
     * 获取最近一次正运动学结果
     *
     * @return 正运动学位姿，若未收到则返回 null
     */
    ForwardKinematicsResult getLastForwardKinematics();

    /**
     * 获取最近一次逆运动学结果
     *
     * @return 逆运动学求解结果，若未收到则返回 null
     */
    InverseKinematicsResult getLastInverseKinematics();
}
