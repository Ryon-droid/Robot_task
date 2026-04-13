package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.mapper.TaskMapper;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.StateTrackService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.PostConstruct;
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

    // 使用线程安全的 PriorityBlockingQueue
    private PriorityBlockingQueue<Task> taskQueue;
    
    // 调度锁，防止竞态条件
    private final ReentrantLock scheduleLock = new ReentrantLock();

    @PostConstruct
    public void init() {
        // 初始化队列，使用自定义 Comparator 防止 NPE（null 值放在最后）
        taskQueue = new PriorityBlockingQueue<>(100, Comparator
            .comparingInt((Task t) -> t.getPriority() != null ? t.getPriority() : Integer.MAX_VALUE)
            .thenComparing(Task::getCreateTime, Comparator.nullsLast(Comparator.naturalOrder())));
        
        // 启动时加载待执行任务
        loadPendingTasks();
    }

    /**
     * 加载待执行任务到队列（启动时调用）
     */
    private void loadPendingTasks() {
        QueryWrapper<Task> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "待执行");
        wrapper.orderByAsc("priority", "create_time");
        // 限制数量，避免内存溢出
        wrapper.last("LIMIT 1000");
        
        List<Task> tasks = taskMapper.selectList(wrapper);
        taskQueue.addAll(tasks);
        log.info("加载 {} 个待执行任务到队列", tasks.size());
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
                    // 分配失败，任务状态已不是"待执行"，跳过
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
        // 1. 先获取空闲机器人（带锁查询）
        Robot robot = getIdleRobot();
        if (robot == null) {
            // 没有空闲机器人，任务放回队列
            taskQueue.offer(task);
            return false;
        }
        
        // 2. 尝试更新任务状态（乐观锁：只有状态为"待执行"时才更新）
        UpdateWrapper<Task> taskUpdate = new UpdateWrapper<>();
        taskUpdate.eq("task_id", task.getTaskId());
        taskUpdate.eq("status", "待执行"); // 乐观锁条件
        taskUpdate.set("status", "执行中");
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
        robotUpdate.eq("status", "空闲"); // 乐观锁条件
        robotUpdate.set("status", "忙碌");
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
            "待执行", 
            "执行中", 
            "任务分配给机器人 " + robot.getRobotId()
        );
        
        log.info("任务 {} 成功分配给机器人 {}", task.getTaskId(), robot.getRobotId());
        return true;
    }
    
    /**
     * 获取空闲机器人
     */
    private Robot getIdleRobot() {
        QueryWrapper<Robot> wrapper = new QueryWrapper<>();
        wrapper.eq("status", "空闲");
        wrapper.orderByAsc("load");
        wrapper.last("LIMIT 1 FOR UPDATE"); // 悲观锁，防止多个线程获取同一个机器人
        return robotMapper.selectOne(wrapper);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean assignTask(String taskId, String robotId) {
        // 此方法现在仅作为外部强制分配任务的入口
        // 内部实现复用 tryAssignTask 的逻辑
        Task task = taskMapper.selectById(taskId);
        if (task == null || !"待执行".equals(task.getStatus())) {
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
            wrapper.eq("status", "执行中");
            List<Task> tasks = taskMapper.selectList(wrapper);

            for (Task task : tasks) {
                String oldStatus = task.getStatus();
                task.setStatus("待执行");
                task.setRobotId(null);
                taskMapper.updateById(task);

                // 记录状态变更
                stateTrackService.recordTaskStateChange(
                    task.getTaskId(), 
                    oldStatus, 
                    "待执行", 
                    "机器人故障，任务重新排队"
                );
                
                // 重新加入队列
                taskQueue.offer(task);
            }

            // 更新机器人状态
            Robot robot = robotMapper.selectById(robotId);
            if (robot != null) {
                robot.setStatus("故障");
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
    
    /**
     * 添加新任务到队列（供外部调用）
     */
    public void addTask(Task task) {
        if (task != null && "待执行".equals(task.getStatus())) {
            taskQueue.offer(task);
            triggerSchedule();
        }
    }
    
    /**
     * 获取队列中待执行任务数量
     */
    public int getPendingTaskCount() {
        return taskQueue.size();
    }
}
