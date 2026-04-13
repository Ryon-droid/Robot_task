package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.entity.Log;
import com.robot.scheduler.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/v1/logs")
public class LogController {

    @Autowired
    private LogService logService;

    /**
     * 获取日志列表
     * GET /api/v1/logs
     */
    @GetMapping
    public Result<List<Map<String, Object>>> getLogList(
            @RequestParam(required = false) String type,
            @RequestParam(required = false) String referenceId) {
        
        List<Log> logs;
        if (type != null && !type.isEmpty()) {
            logs = logService.getLogsByType(type);
        } else if (referenceId != null && !referenceId.isEmpty()) {
            logs = logService.getLogsByReferenceId(referenceId);
        } else {
            logs = logService.getLogList();
        }
        
        List<Map<String, Object>> response = new java.util.ArrayList<>();
        for (Log log : logs) {
            Map<String, Object> logMap = Map.of(
                "logId", log.getLogId(),
                "type", log.getLogType(),
                "message", log.getMessage(),
                "referenceId", log.getReferenceId(),
                "createdAt", log.getCreateTime().getTime()
            );
            response.add(logMap);
        }
        
        return Result.success(response);
    }
}