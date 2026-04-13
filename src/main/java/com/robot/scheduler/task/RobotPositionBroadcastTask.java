package com.robot.scheduler.task;

import com.robot.scheduler.websocket.RobotWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class RobotPositionBroadcastTask {

    @Autowired
    private RobotWebSocketHandler robotWebSocketHandler;

    /**
     * 每100ms广播一次机器人位置
     * 前端可以实时接收位置更新
     */
    @Scheduled(fixedRate = 100)
    public void broadcastPositions() {
        robotWebSocketHandler.broadcastRobotPositions();
    }
}