package com.robot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("map_live")
public class MapLive {

    @TableId(value = "live_id", type = IdType.INPUT)
    private String liveId;

    private String mapId;

    private Double resolution;

    private Integer width;

    private Integer height;

    @TableField("origin_x")
    private Double originX;

    @TableField("origin_y")
    private Double originY;

    @TableField("origin_yaw")
    private Double originYaw;

    @TableField("grid_data")
    private String gridData;

    private String obstacles;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
