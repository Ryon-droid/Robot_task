package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.service.RosBridgeService;
import com.robot.scheduler.service.RobotService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.*;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

/**
 * ROS Bridge HTTP 客户端实现（适配 http_scheduler_bridge.py）
 *
 * 通过 HTTP REST API 与 SLAM 端通信：
 *   - 定时 GET /api/v1/scheduler/ros/pose 轮询位姿
 *   - POST /api/v1/scheduler/ros/goal 发送导航目标
 *   - GET /healthz 查询连接状态
 *
 * 注意：Python 端未提供 /map HTTP 接口，地图订阅功能已移除。
 */
@Slf4j
@Service
public class RosBridgeServiceImpl implements RosBridgeService {

    @Value("${rosbridge.http.url:http://172.16.25.219:9090}")
    private String rosBridgeHttpUrl;

    @Value("${rosbridge.default-robot-code:}")
    private String defaultRobotCode;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RobotService robotService;

    @Autowired
    private RobotMapper robotMapper;

    private final RestTemplate restTemplate = new RestTemplate();
    private final AtomicLong poseFetchCount = new AtomicLong(0);
    private final AtomicLong goalSendCount = new AtomicLong(0);

    // ==================== 定时轮询位姿 ====================

    @Scheduled(fixedRate = 1000)
    public void fetchPose() {
        try {
            String url = rosBridgeHttpUrl + "/api/v1/scheduler/ros/pose";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                return;
            }

            Map<String, Object> data = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
            if (!Boolean.TRUE.equals(data.get("ok"))) {
                return;
            }

            Map<String, Object> pose = (Map<String, Object>) data.get("pose");
            if (pose == null) return;

            Map<String, Object> position = (Map<String, Object>) pose.get("position");
            Map<String, Object> orientation = (Map<String, Object>) pose.get("orientation");

            double x = parseDouble(position != null ? position.get("x") : null, 0.0);
            double y = parseDouble(position != null ? position.get("y") : null, 0.0);
            double qz = parseDouble(orientation != null ? orientation.get("z") : null, 0.0);
            double qw = parseDouble(orientation != null ? orientation.get("w") : null, 1.0);
            double yaw = quaternionToYaw(qz, qw);

            String robotCode = resolveRobotCode();
            if (robotCode == null) {
                log.warn("HTTP 轮询位姿但无法解析 robotCode，跳过更新");
                return;
            }

            Robot robot = findRobotByCode(robotCode);
            if (robot != null) {
                robotService.updateRobotPose(robot.getRobotId(), x, y, yaw);
                long count = poseFetchCount.incrementAndGet();
                if (count == 1 || count % 100 == 0) {
                    log.info("HTTP 轮询更新机器人位姿: {} -> ({:.2f}, {:.2f}, {:.2f})", robotCode, x, y, yaw);
                }
            } else {
                log.warn("未找到 robotCode={} 对应的机器人", robotCode);
            }
        } catch (Exception e) {
            log.debug("HTTP 轮询位姿失败: {}", e.getMessage());
        }
    }

    // ==================== 对外接口 ====================

    @Override
    public boolean sendNavigationGoal(String robotCode, double x, double y, double yaw) {
        try {
            // 先更新内存目标与 Mock 路径
            Robot robot = findRobotByCode(robotCode);
            if (robot != null) {
                robotService.setRobotGoal(robot.getRobotId(), x, y, yaw);
            }

            // HTTP POST 发送导航目标（支持 {x, y, yaw} 简写格式）
            Map<String, Object> body = new HashMap<>();
            body.put("x", x);
            body.put("y", y);
            body.put("yaw", yaw);

            String url = rosBridgeHttpUrl + "/api/v1/scheduler/ros/goal";
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, headers);

            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            boolean success = response.getStatusCode().is2xxSuccessful();
            if (success) {
                goalSendCount.incrementAndGet();
                log.info("HTTP 发送导航目标成功: robot={}, ({}, {}, {})", robotCode, x, y, yaw);
            } else {
                log.warn("HTTP 发送导航目标失败，响应码: {}", response.getStatusCode());
            }
            return success;
        } catch (Exception e) {
            log.error("HTTP 发送导航目标失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getConnectionStatus() {
        Map<String, Object> status = new HashMap<>();
        try {
            String url = rosBridgeHttpUrl + "/healthz";
            ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
            boolean connected = response.getStatusCode().is2xxSuccessful();
            status.put("connected", connected);
            status.put("httpUrl", rosBridgeHttpUrl);
            status.put("poseFetchCount", poseFetchCount.get());
            status.put("goalSendCount", goalSendCount.get());
            if (connected && response.getBody() != null) {
                Map<String, Object> health = objectMapper.readValue(response.getBody(), new TypeReference<Map<String, Object>>() {});
                status.put("health", health);
            }
        } catch (Exception e) {
            status.put("connected", false);
            status.put("error", e.getMessage());
        }
        return status;
    }

    // ==================== 辅助方法 ====================

    private String resolveRobotCode() {
        if (defaultRobotCode != null && !defaultRobotCode.isEmpty()) {
            return defaultRobotCode;
        }
        List<Robot> robots = robotMapper.selectList(
                new QueryWrapper<Robot>().isNotNull("robot_code").last("LIMIT 1"));
        if (!robots.isEmpty() && robots.get(0).getRobotCode() != null) {
            return robots.get(0).getRobotCode();
        }
        return null;
    }

    private Robot findRobotByCode(String robotCode) {
        return robotMapper.selectOne(
                new QueryWrapper<Robot>().eq("robot_code", robotCode));
    }

    private double quaternionToYaw(double qz, double qw) {
        return Math.atan2(2.0 * qw * qz, qw * qw - qz * qz);
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
