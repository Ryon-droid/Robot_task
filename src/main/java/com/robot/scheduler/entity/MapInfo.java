package com.robot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("map")
public class MapInfo {

    @TableId(value = "map_id", type = IdType.INPUT)
    private String mapId;

    private String mapName;

    @TableField("pgm_data")
    private byte[] pgmData;

    @TableField("yaml_data")
    private String yamlData;

    private Double resolution;

    @TableField("origin_x")
    private Double originX;

    @TableField("origin_y")
    private Double originY;

    @TableField("origin_yaw")
    private Double originYaw;

    private Integer width;

    private Integer height;

    private Integer negate;

    @TableField("occupied_thresh")
    private Double occupiedThresh;

    @TableField("free_thresh")
    private Double freeThresh;

    @TableField("is_active")
    private Integer isActive;

    @TableField("create_time")
    private LocalDateTime createTime;

    @TableField("update_time")
    private LocalDateTime updateTime;
}
