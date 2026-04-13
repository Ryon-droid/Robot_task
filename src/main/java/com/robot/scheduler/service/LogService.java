package com.robot.scheduler.service;

import com.robot.scheduler.entity.Log;

import java.util.List;

public interface LogService {
    
    /**
     * 创建日志
     */
    Log createLog(String logType, String message, String referenceId);
    
    /**
     * 获取日志列表
     */
    List<Log> getLogList();
    
    /**
     * 根据类型获取日志
     */
    List<Log> getLogsByType(String logType);
    
    /**
     * 根据关联ID获取日志
     */
    List<Log> getLogsByReferenceId(String referenceId);
}