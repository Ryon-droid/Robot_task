package com.robot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("log")
public class Log {
    @TableId(value = "log_id", type = IdType.AUTO)
    private Long logId;
    private String logType;    // 日志类型：TASK, ROBOT, SYSTEM
    private String message;     // 日志内容
    private String referenceId; // 关联ID（如任务ID、机器人ID）
    private Date createTime;    // 创建时间
}