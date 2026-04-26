package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.entity.Robot;
import com.robot.scheduler.mapper.RobotMapper;
import com.robot.scheduler.service.RosBridgeService;
import com.robot.scheduler.service.RobotService;
import com.robot.scheduler.service.SLAMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.net.URI;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

@Slf4j
@Service
public class RosBridgeServiceImpl implements RosBridgeService {

    @Value("${rosbridge.websocket.url:ws://localhost:9090}")
    private String rosBridgeUrl;

    @Value("${rosbridge.topics.map:/map}")
    private String mapTopic;

    @Value("${rosbridge.topics.pose:/amcl_pose}")
    private String poseTopic;

    @Value("${rosbridge.topics.goal:/goal_pose}")
    private String goalTopic;

    @Value("${rosbridge.default-robot-code:}")
    private String defaultRobotCode;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SLAMService slamService;

    @Autowired
    private RobotService robotService;

    @Autowired
    private RobotMapper robotMapper;

    private WebSocketSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong mapMessageCount = new AtomicLong(0);
    private final AtomicLong poseMessageCount = new AtomicLong(0);
    private volatile long lastConnectAttempt = 0;

    @PostConstruct
    public void init() {
        connect();
    }

    @PreDestroy
    public void destroy() {
        disconnect();
    }

    @Scheduled(fixedRate = 30000)
    public void healthCheck() {
        if (!connected.get() || session == null || !session.isOpen()) {
            log.warn("RosBridge 连接断开，尝试重连...");
            connect();
        }
    }

    // ==================== 连接管理 ====================

    private synchronized void connect() {
        if (connected.get() && session != null && session.isOpen()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastConnectAttempt < 10000) {
            return; // 10 秒内不重试
        }
        lastConnectAttempt = now;

        StandardWebSocketClient client = new StandardWebSocketClient();
        try {
            session = client.doHandshake(new RosBridgeHandler(), new WebSocketHttpHeaders(), new URI(rosBridgeUrl))
                    .get(10, TimeUnit.SECONDS);
            connected.set(true);
            log.info("RosBridge 连接成功: {}", rosBridgeUrl);

            subscribe(mapTopic, "nav_msgs/OccupancyGrid");
            subscribe(poseTopic, "geometry_msgs/PoseWithCovarianceStamped");
        } catch (Exception e) {
            log.error("RosBridge 连接失败: {} - {}", rosBridgeUrl, e.getMessage());
            connected.set(false);
        }
    }

    private synchronized void disconnect() {
        connected.set(false);
        if (session != null && session.isOpen()) {
            try {
                session.close();
            } catch (Exception ignored) {
            }
        }
        session = null;
    }

    private void subscribe(String topic, String type) {
        if (!connected.get() || session == null || !session.isOpen()) {
            return;
        }
        try {
            Map<String, Object> msg = new HashMap<>();
            msg.put("op", "subscribe");
            msg.put("topic", topic);
            msg.put("type", type);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(msg)));
            log.info("订阅 ROS 话题: {} ({})", topic, type);
        } catch (Exception e) {
            log.error("订阅 {} 失败: {}", topic, e.getMessage());
        }
    }

    // ==================== 消息处理 ====================

    private class RosBridgeHandler extends TextWebSocketHandler {
        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            Map<String, Object> rosMsg;
            try {
                rosMsg = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("解析 RosBridge 消息失败: {}", e.getMessage());
                return;
            }

            String op = String.valueOf(rosMsg.getOrDefault("op", ""));
            if (!"publish".equals(op)) {
                // 忽略非 publish 消息（如 service_response、status 等）
                return;
            }

            String topic = String.valueOf(rosMsg.getOrDefault("topic", ""));
            if (mapTopic.equals(topic)) {
                handleMapMessage(rosMsg);
            } else if (poseTopic.equals(topic)) {
                handlePoseMessage(rosMsg);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("RosBridge 传输错误: {}", exception.getMessage());
            connected.set(false);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
            log.warn("RosBridge 连接关闭: {}", status);
            connected.set(false);
        }
    }

    private void handleMapMessage(Map<String, Object> rosMsg) {
        Object msgObj = rosMsg.get("msg");
        if (!(msgObj instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) msgObj;

        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) msg.get("info");
        if (info == null) {
            return;
        }

        Map<String, Object> mapData = new HashMap<>();
        mapData.put("resolution", parseDouble(info.get("resolution"), 0.05));
        mapData.put("width", parseInt(info.get("width"), 0));
        mapData.put("height", parseInt(info.get("height"), 0));

        @SuppressWarnings("unchecked")
        Map<String, Object> origin = (Map<String, Object>) info.get("origin");
        if (origin != null) {
            @SuppressWarnings("unchecked")
            Map<String, Object> position = (Map<String, Object>) origin.get("position");
            if (position != null) {
                Map<String, Object> originMap = new HashMap<>();
                originMap.put("x", parseDouble(position.get("x"), 0.0));
                originMap.put("y", parseDouble(position.get("y"), 0.0));
                mapData.put("origin", originMap);
            }
        }

        Object data = msg.get("data");
        if (data instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> dataList = (List<Object>) data;
            int[] grid = new int[dataList.size()];
            for (int i = 0; i < dataList.size(); i++) {
                grid[i] = parseInt(dataList.get(i), -1);
            }
            mapData.put("data", grid);
        }

        slamService.updateMapData(mapData);
        long count = mapMessageCount.incrementAndGet();
        if (count == 1 || count % 60 == 0) {
            log.info("收到并更新地图数据: {}x{}，累计 {} 次", mapData.get("width"), mapData.get("height"), count);
        }
    }

    private void handlePoseMessage(Map<String, Object> rosMsg) {
        Object msgObj = rosMsg.get("msg");
        if (!(msgObj instanceof Map)) {
            return;
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> msg = (Map<String, Object>) msgObj;

        @SuppressWarnings("unchecked")
        Map<String, Object> poseWithCovariance = (Map<String, Object>) msg.get("pose");
        if (poseWithCovariance == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> pose = (Map<String, Object>) poseWithCovariance.get("pose");
        if (pose == null) {
            return;
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> position = (Map<String, Object>) pose.get("position");
        @SuppressWarnings("unchecked")
        Map<String, Object> orientation = (Map<String, Object>) pose.get("orientation");

        double x = parseDouble(position != null ? position.get("x") : null, 0.0);
        double y = parseDouble(position != null ? position.get("y") : null, 0.0);
        double qz = parseDouble(orientation != null ? orientation.get("z") : null, 0.0);
        double qw = parseDouble(orientation != null ? orientation.get("w") : null, 1.0);
        double yaw = quaternionToYaw(qz, qw);

        String robotCode = resolveRobotCode();
        if (robotCode == null) {
            log.warn("RosBridge 收到位姿但无法解析 robotCode，跳过更新");
            return;
        }

        Robot robot = findRobotByCode(robotCode);
        if (robot != null) {
            robotService.updateRobotPose(robot.getRobotId(), x, y, yaw);
            long count = poseMessageCount.incrementAndGet();
            if (count == 1 || count % 100 == 0) {
                log.info("更新机器人位姿: {} -> ({:.2f}, {:.2f}, {:.2f})", robotCode, x, y, yaw);
            }
        } else {
            log.warn("未找到 robotCode={} 对应的机器人", robotCode);
        }
    }

    // ==================== 对外接口 ====================

    @Override
    public boolean sendNavigationGoal(String robotCode, double x, double y, double yaw) {
        if (!ensureConnected()) {
            log.warn("RosBridge 未连接，无法发送导航目标");
            return false;
        }

        try {
            // 先更新内存目标与 Mock 路径
            Robot robot = findRobotByCode(robotCode);
            if (robot != null) {
                robotService.setRobotGoal(robot.getRobotId(), x, y, yaw);
            }

            // 发送 ROS 导航目标
            double[] q = yawToQuaternion(yaw);
            Map<String, Object> goalMsg = buildGoalMessage(x, y, q);
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(goalMsg)));
            log.info("发送导航目标到 ROS: robot={}, ({}, {}, {})", robotCode, x, y, yaw);
            return true;
        } catch (Exception e) {
            log.error("发送导航目标失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getConnectionStatus() {
        boolean isOpen = connected.get() && session != null && session.isOpen();
        Map<String, Object> status = new HashMap<>();
        status.put("connected", isOpen);
        status.put("url", rosBridgeUrl);
        status.put("mapTopic", mapTopic);
        status.put("poseTopic", poseTopic);
        status.put("goalTopic", goalTopic);
        status.put("mapMessageCount", mapMessageCount.get());
        status.put("poseMessageCount", poseMessageCount.get());
        return status;
    }

    // ==================== 辅助方法 ====================

    private boolean ensureConnected() {
        if (connected.get() && session != null && session.isOpen()) {
            return true;
        }
        connect();
        return connected.get() && session != null && session.isOpen();
    }

    private String resolveRobotCode() {
        if (defaultRobotCode != null && !defaultRobotCode.isEmpty()) {
            return defaultRobotCode;
        }
        // 尝试找数据库中第一个有 robot_code 的机器人
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

    private Map<String, Object> buildGoalMessage(double x, double y, double[] q) {
        Map<String, Object> goalMsg = new HashMap<>();
        goalMsg.put("op", "publish");
        goalMsg.put("topic", goalTopic);

        Map<String, Object> stamp = new HashMap<>();
        stamp.put("sec", 0);
        stamp.put("nanosec", 0);

        Map<String, Object> header = new HashMap<>();
        header.put("stamp", stamp);
        header.put("frame_id", "map");

        Map<String, Object> position = new HashMap<>();
        position.put("x", x);
        position.put("y", y);
        position.put("z", 0.0);

        Map<String, Object> orient = new HashMap<>();
        orient.put("x", 0.0);
        orient.put("y", 0.0);
        orient.put("z", q[2]);
        orient.put("w", q[3]);

        Map<String, Object> pose = new HashMap<>();
        pose.put("position", position);
        pose.put("orientation", orient);

        Map<String, Object> msg = new HashMap<>();
        msg.put("header", header);
        msg.put("pose", pose);

        goalMsg.put("msg", msg);
        return goalMsg;
    }

    private double[] yawToQuaternion(double yaw) {
        double half = yaw / 2.0;
        return new double[]{0.0, 0.0, Math.sin(half), Math.cos(half)};
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

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }
}
