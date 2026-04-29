package com.robot.scheduler.dto;

import lombok.Data;

import java.util.List;

/**
 * 逆运动学求解结果（算法端下行报文2）
 */
@Data
public class InverseKinematicsResult {

    /**
     * 目标物品名称
     */
    private String targetObj;

    /**
     * 逆运动学求解状态：true=有解，false=无解/超程/奇异位形
     */
    private boolean ikSolve;

    /**
     * 机械臂总关节数
     */
    private int jointCount;

    /**
     * 各关节旋转角度数组，单位弧度（rad）；无解时全部为 0.0
     */
    private List<Double> jointValue;
}
