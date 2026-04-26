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

    // 动态优先级规划相关字段
    private Date deadline;                 // 任务截止时间
    private Integer estimatedDuration;     // 预估执行时长(秒)
    private Double dynamicPriorityScore;   // 动态优先级分数(越低越优先)

    @Override
    public int compareTo(Task o) {
        if (o == null) return -1;

        // 主排序：dynamicPriorityScore 升序（null 视为最大值）
        double thisScore = this.dynamicPriorityScore != null ? this.dynamicPriorityScore : Double.MAX_VALUE;
        double otherScore = o.dynamicPriorityScore != null ? o.dynamicPriorityScore : Double.MAX_VALUE;
        int scoreCompare = Double.compare(thisScore, otherScore);
        if (scoreCompare != 0) {
            return scoreCompare;
        }

        // 次排序：createTime 升序
        if (this.createTime == null && o.createTime == null) return 0;
        if (this.createTime == null) return 1;
        if (o.createTime == null) return -1;
        return this.createTime.compareTo(o.createTime);
    }
}
