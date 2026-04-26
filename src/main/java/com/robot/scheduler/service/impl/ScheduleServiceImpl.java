package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.mapper.TaskMapper;
import com.robot.scheduler.service.DataServiceClient;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.StateTrackService;
import com.robot.scheduler.service.TaskPriorityPlanner;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Service
public class ScheduleServiceImpl implements ScheduleService {

    @Autowired
    private TaskMapper taskMapper;

    @Autowired
    private RobotMapper robotMapper;

    @Autowired
    private StateTrackService stateTrackService;

    @Autowired
    private TaskPriorityPlanner taskPriorityPlanner;

    @Autowired
    private DataServiceClient dataServiceClient;

    // 使用线程安全的 PriorityBlockingQueue
    private PriorityBlockingQueue<Task> taskQueue;

    // 调度锁，防止竞态条件
    private final ReentrantLock scheduleLock = new ReentrantLock();

    @PostConstruct
    public void init() {
        // 初始化队列，使用自定义 Comparator 防止 NPE（null 值放在最后）
        taskQueue = new PriorityBlockingQueue<>(100, Comparator
                .comparingDouble((Task t) -> t.getDynamicPriorityScore() != null ? t.getDynamicPriorityScore() : Double.MAX_VALUE)
                .thenComparing(Task::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));

        // 启动时加载待执行任务
        refreshQueueFromDb();
    }

    /**
     * 从数据库刷新待执行任务队列
     */
    @Override
    public void refreshQueueFromDb() {
        scheduleLock.lock();
        try {
            taskQueue.clear();

            QueryWrapper<Task> wrapper = new QueryWrapper<>();
            wrapper.eq("status", StatusConstant.TASK_STATUS_PENDING);
            wrapper.orderByAsc("dynamic_priority_score", "create_time");
            wrapper.last("LIMIT 1000");

            List<Task> tasks = taskMapper.selectList(wrapper);
            taskQueue.addAll(tasks);
            log.info("从数据库刷新队列，加载 {} 个待执行任务", tasks.size());
        } finally {
            scheduleLock.unlock();
        }
    }

    @Override
    public void triggerSchedule() {
        // 加锁防止竞态条件
        if (!scheduleLock.tryLock()) {
            log.debug("调度正在进行中，跳过本次触发");
            return;
        }

        try {
            // 尝试从队列分配任务
            while (!taskQueue.isEmpty()) {
                Task task = taskQueue.poll();
                if (task == null) continue;

                // 尝试分配任务（使用乐观锁确保原子性）
                boolean assigned = tryAssignTask(task);

                if (!assigned) {
                    // 分配失败，任务状态已不是"QUEUED"，跳过
                    log.debug("任务 {} 分配失败，跳过", task.getTaskId());
                }
            }
        } finally {
            scheduleLock.unlock();
        }
    }

    /**
     * 尝试分配任务（使用数据库乐观锁）
     */
    @Transactional(rollbackFor = Exception.class)
    public boolean tryAssignTask(Task task) {
        // 1. 先获取空闲机器人
        Robot robot = getIdleRobot();
        if (robot == null) {
            // 没有空闲机器人，不放回队列，等待下次调度
            log.debug("没有空闲机器人，任务 {} 等待下次调度", task.getTaskId());
            return false;
        }

        // 2. 尝试更新任务状态（乐观锁：只有状态为 QUEUED 时才更新）
        UpdateWrapper<Task> taskUpdate = new UpdateWrapper<>();
        taskUpdate.eq("task_id", task.getTaskId());
        taskUpdate.eq("status", StatusConstant.TASK_STATUS_PENDING); // 乐观锁条件
        taskUpdate.set("status", StatusConstant.TASK_STATUS_RUNNING);
        taskUpdate.set("robot_id", robot.getRobotId());

        int taskUpdateCount = taskMapper.update(null, taskUpdate);
        if (taskUpdateCount == 0) {
            // 任务状态已被其他线程修改，放弃此任务
            log.warn("任务 {} 状态已变更，放弃分配", task.getTaskId());
            return false;
        }

        // 3. 更新机器人状态为忙碌
        UpdateWrapper<Robot> robotUpdate = new UpdateWrapper<>();
        robotUpdate.eq("robot_id", robot.getRobotId());
        robotUpdate.eq("status", StatusConstant.ROBOT_STATUS_IDLE); // 乐观锁条件
        robotUpdate.set("status", StatusConstant.ROBOT_STATUS_BUSY);
        robotUpdate.setSql("load = load + 1");

        int robotUpdateCount = robotMapper.update(null, robotUpdate);
        if (robotUpdateCount == 0) {
            // 机器人状态已被其他线程修改，回滚任务状态
            log.error("机器人 {} 状态已变更，任务 {} 分配失败", robot.getRobotId(), task.getTaskId());
            throw new RuntimeException("机器人状态已变更");
        }

        // 4. 记录状态变更
        stateTrackService.recordTaskStateChange(
                task.getTaskId(),
                StatusConstant.TASK_STATUS_PENDING,
                StatusConstant.TASK_STATUS_RUNNING,
                "任务分配给机器人 " + robot.getRobotId()
        );

        // 5. 上报数据服务
        dataServiceClient.reportTaskUpdated(task.getTaskId(), robot.getRobotId(), StatusConstant.TASK_STATUS_RUNNING, task.getDynamicPriorityScore());
        dataServiceClient.reportTaskStatusChanged(task.getTaskId(), StatusConstant.TASK_STATUS_PENDING, StatusConstant.TASK_STATUS_RUNNING, "任务分配给机器人 " + robot.getRobotId());

        log.info("任务 {} 成功分配给机器人 {}", task.getTaskId(), robot.getRobotId());
        return true;
    }

    /**
     * 获取空闲机器人（使用乐观锁策略）
     */
    private Robot getIdleRobot() {
        QueryWrapper<Robot> wrapper = new QueryWrapper<>();
        wrapper.eq("status", StatusConstant.ROBOT_STATUS_IDLE);
        wrapper.orderByAsc("load");
        wrapper.last("LIMIT 1");
        return robotMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignTask(String taskId, String robotId) {
        // 此方法现在仅作为外部强制分配任务的入口
        // robotId 参数保留用于接口兼容，实际内部按负载最小策略选取
        Task task = taskMapper.selectById(taskId);
        if (task == null || !StatusConstant.TASK_STATUS_PENDING.equals(task.getStatus())) {
            return false;
        }
        return tryAssignTask(task);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void handleRobotError(String robotId) {
        try {
            // 查找该机器人正在执行的任务
            QueryWrapper<Task> wrapper = new QueryWrapper<>();
            wrapper.eq("robot_id", robotId);
            wrapper.eq("status", StatusConstant.TASK_STATUS_RUNNING);
            List<Task> tasks = taskMapper.selectList(wrapper);

            for (Task task : tasks) {
                String oldStatus = task.getStatus();
                task.setStatus(StatusConstant.TASK_STATUS_PENDING);
                task.setRobotId(null);
                taskMapper.updateById(task);

                // 记录状态变更
                stateTrackService.recordTaskStateChange(
                        task.getTaskId(),
                        oldStatus,
                        StatusConstant.TASK_STATUS_PENDING,
                        "机器人故障，任务重新排队"
                );

                // 重新加入队列
                taskQueue.offer(task);
            }

            // 更新机器人状态
            Robot robot = robotMapper.selectById(robotId);
            if (robot != null) {
                robot.setStatus(StatusConstant.ROBOT_STATUS_ERROR);
                robot.setLoad(0);
                robotMapper.updateById(robot);
            }

            log.info("机器人 {} 故障处理完成，重新调度 {} 个任务", robotId, tasks.size());

            // 触发重新调度
            triggerSchedule();
        } catch (Exception e) {
            log.error("处理机器人故障失败: robotId={}", robotId, e);
            throw e;
        }
    }

    @Override
    public List<Task> getPendingQueue() {
        scheduleLock.lock();
        try {
            List<Task> list = new ArrayList<>(taskQueue);
            Collections.sort(list);
            return list;
        } finally {
            scheduleLock.unlock();
        }
    }

    @Override
    public void recalculatePriorities() {
        int count = taskPriorityPlanner.recalculateAllPendingTasks();
        if (count > 0) {
            refreshQueueFromDb();
            triggerSchedule();
        }
    }

    /**
     * 定时自动重算动态优先级（默认每 30 秒）
     */
    @Scheduled(fixedRateString = "${scheduler.priority.recalculation-interval-ms:30000}")
    public void scheduledPriorityRecalculation() {
        try {
            recalculatePriorities();
        } catch (Exception e) {
            log.error("定时优先级重算失败", e);
        }
    }
}
