package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.service.ScheduleService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/scheduler/schedule")
public class ScheduleController {

    @Autowired
    private ScheduleService scheduleService;

    // 手动触发调度
    @PostMapping("/trigger")
    public Result<String> triggerSchedule() {
        scheduleService.triggerSchedule();
        return Result.success("调度触发成功");
    }
}
