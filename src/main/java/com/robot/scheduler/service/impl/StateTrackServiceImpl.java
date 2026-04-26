package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.TaskRecord;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.mapper.TaskRecordMapper;
import com.robot.scheduler.service.DataServiceClient;
import com.robot.scheduler.service.StateTrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Date;
import java.util.List;
import java.util.UUID;

@Slf4j
@Service
public class StateTrackServiceImpl implements StateTrackService {

    @Autowired
    private TaskRecordMapper taskRecordMapper;

    @Autowired
    private RobotMapper robotMapper;

    @Autowired
    private DataServiceClient dataServiceClient;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void recordTaskStateChange(String taskId, String oldStatus, String newStatus, String reason) {
        TaskRecord record = new TaskRecord();
        record.setRecordId(UUID.randomUUID().toString().replace("-", ""));
        record.setTaskId(taskId);
        record.setOldStatus(oldStatus);
        record.setNewStatus(newStatus);
        record.setChangeTime(new Date());
        record.setChangeReason(reason);
        taskRecordMapper.insert(record);
    }

    @Override
    public List<TaskRecord> getTaskStateHistory(String taskId) {
        QueryWrapper<TaskRecord> wrapper = new QueryWrapper<>();
        wrapper.eq("task_id", taskId);
        wrapper.orderByDesc("change_time");
        return taskRecordMapper.selectList(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateRobotState(String robotId, String status) {
        // 使用乐观锁更新机器人状态
        UpdateWrapper<Robot> updateWrapper = new UpdateWrapper<>();
        updateWrapper.eq("robot_id", robotId);

        updateWrapper.set("status", status);
        if (StatusConstant.ROBOT_STATUS_IDLE.equals(status)) {
            updateWrapper.set("load", 0);
        }

        int updateCount = robotMapper.update(null, updateWrapper);
        if (updateCount == 0) {
            log.warn("更新机器人 {} 状态失败，可能机器人不存在", robotId);
        } else {
            log.info("机器人 {} 状态更新为 {}", robotId, status);
            // 上报数据服务
            Robot robot = robotMapper.selectById(robotId);
            if (robot != null) {
                dataServiceClient.reportRobotStatus(robot);
            }
        }
    }
}
