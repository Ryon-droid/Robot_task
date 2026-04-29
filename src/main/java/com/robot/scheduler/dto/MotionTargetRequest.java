package com.robot.scheduler.dto;

import lombok.Data;

/**
 * 发送给运动学算法端的目标物体请求
 */
@Data
public class MotionTargetRequest {

    /**
     * 目标物品名称
     */
    private String objName;

    /**
     * 世界坐标系 X 坐标（米）
     */
    private double x;

    /**
     * 世界坐标系 Y 坐标（米）
     */
    private double y;

    /**
     * 世界坐标系 Z 坐标（米）
     */
    private double z;
}
