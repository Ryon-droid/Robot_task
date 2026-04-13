package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.StateTrackService;
import com.robot.scheduler.service.TaskService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/v1/scheduler/robot")
public class RobotCallbackController {

    @Autowired
    private TaskService taskService;

    @Autowired
    private StateTrackService stateTrackService;

    @Autowired
    private ScheduleService scheduleService;

    // 机器人状态回调
    @PostMapping("/callback")
    public Result<String> robotCallback(@RequestBody Map<String, Object> callbackData) {
        String robotId = (String) callbackData.get("robotId");
        String taskId = (String) callbackData.get("taskId");
        String status = (String) callbackData.get("status");
        String reason = (String) callbackData.get("reason");

        // 更新任务状态
        if (taskId != null) {
            taskService.updateTaskStatus(taskId, status, reason);
        }

        // 更新机器人状态
        if ("已完成".equals(status)) {
            stateTrackService.updateRobotState(robotId, "空闲");
            // 重新触发调度
            scheduleService.triggerSchedule();
        } else if ("执行失败".equals(status)) {
            stateTrackService.updateRobotState(robotId, "空闲");
            // 重新触发调度
            scheduleService.triggerSchedule();
        } else if ("故障".equals(status)) {
            scheduleService.handleRobotError(robotId);
        }

        return Result.success("回调处理成功");
    }
}
