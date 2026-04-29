package com.robot.scheduler.dto;

import lombok.Data;

/**
 * 正运动学末端位姿结果（算法端下行报文1）
 */
@Data
public class ForwardKinematicsResult {

    /**
     * 目标物品名称
     */
    private String targetObj;

    /**
     * 机械臂末端目标位姿
     */
    private TargetPose targetPose;

    @Data
    public static class TargetPose {
        private double x;      // 米（m）
        private double y;      // 米（m）
        private double z;      // 米（m）
        private double roll;   // 弧度（rad）
        private double pitch;  // 弧度（rad）
        private double yaw;    // 弧度（rad）
    }
}
