package com.robot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("robot")
public class Robot {
    @TableId(value = "robot_id", type = IdType.INPUT)
    private String robotId;
    private String robotName;
    private String robotCode;
    private String status;
    private Integer load;
    private Date lastHeartbeat;
    private Integer battery;  // 电池电量（百分比）
    
    // 位置坐标（SLAM地图坐标系，单位：米）
    private Double x;
    private Double y;
    private Double yaw;  // 朝向角度（弧度）
}