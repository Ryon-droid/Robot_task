package com.robot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("task_record")
public class TaskRecord {
    @TableId(value = "record_id", type = IdType.INPUT)
    private String recordId;
    private String taskId;
    private String oldStatus;
    private String newStatus;
    private Date changeTime;
    private String changeReason;
}
