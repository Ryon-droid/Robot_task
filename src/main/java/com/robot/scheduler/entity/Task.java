package com.robot.scheduler.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.util.Date;

@Data
@TableName("task")
public class Task implements Comparable<Task> {
    @TableId(value = "task_id", type = IdType.INPUT)
    private String taskId;
    private String taskName;
    private String commandType;  // 替代 taskType
    private Integer priority;     // 1-5，1最高
    private String robotId;
    private String robotCode;     // 新增字段
    private String status;        // QUEUED → RUNNING → SUCCESS / FAILED
    private String taskParams;    // JSON格式参数
    private Date createTime;
    private Date startTime;
    private Date finishTime;
    private String failReason;

    @Override
    public int compareTo(Task o) {
        // 优先级比较，数字越小优先级越高
        return this.priority - o.priority;
    }
}