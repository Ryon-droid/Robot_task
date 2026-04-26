package com.robot.scheduler.common;

public class StatusConstant {
    // 机器人状态
    public static final String ROBOT_STATUS_IDLE = "空闲";
    public static final String ROBOT_STATUS_BUSY = "忙碌";
    public static final String ROBOT_STATUS_ERROR = "故障";

    // 任务状态
    public static final String TASK_STATUS_PENDING = "QUEUED";
    public static final String TASK_STATUS_RUNNING = "RUNNING";
    public static final String TASK_STATUS_COMPLETED = "SUCCESS";
    public static final String TASK_STATUS_FAILED = "FAILED";

    // 任务优先级（1-5级，1最高）
    public static final int PRIORITY_HIGHEST = 1;
    public static final int PRIORITY_HIGH = 2;
    public static final int PRIORITY_NORMAL = 3;
    public static final int PRIORITY_LOW = 4;
    public static final int PRIORITY_LOWEST = 5;
}
