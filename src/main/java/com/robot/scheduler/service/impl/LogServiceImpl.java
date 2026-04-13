package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.robot.scheduler.entity.Log;
import com.robot.scheduler.mapper.LogMapper;
import com.robot.scheduler.service.LogService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.Date;
import java.util.List;

@Service
public class LogServiceImpl implements LogService {

    @Autowired
    private LogMapper logMapper;

    @Override
    public Log createLog(String logType, String message, String referenceId) {
        Log log = new Log();
        log.setLogType(logType);
        log.setMessage(message);
        log.setReferenceId(referenceId);
        log.setCreateTime(new Date());
        logMapper.insert(log);
        return log;
    }

    @Override
    public List<Log> getLogList() {
        QueryWrapper<Log> queryWrapper = new QueryWrapper<>();
        queryWrapper.orderByDesc("create_time");
        return logMapper.selectList(queryWrapper);
    }

    @Override
    public List<Log> getLogsByType(String logType) {
        QueryWrapper<Log> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("log_type", logType);
        queryWrapper.orderByDesc("create_time");
        return logMapper.selectList(queryWrapper);
    }

    @Override
    public List<Log> getLogsByReferenceId(String referenceId) {
        QueryWrapper<Log> queryWrapper = new QueryWrapper<>();
        queryWrapper.eq("reference_id", referenceId);
        queryWrapper.orderByDesc("create_time");
        return logMapper.selectList(queryWrapper);
    }
}