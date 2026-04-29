package com.robot.scheduler.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.common.StatusConstant;
import com.robot.scheduler.dto.ForwardKinematicsResult;
import com.robot.scheduler.dto.InverseKinematicsResult;
import com.robot.scheduler.entity.Task;
import com.robot.scheduler.service.LogService;
import com.robot.scheduler.service.MotionService;
import com.robot.scheduler.service.ScheduleService;
import com.robot.scheduler.service.TaskService;
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
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 视觉-运动学算法 WebSocket 客户端实现
 * <p>
 * 连接外部运动学算法服务端，发送目标物体坐标，接收正/逆运动学计算结果。
 * 当逆运动学求解成功（ik_solve=true）时，自动创建 GRAB 任务并触发调度。
 */
@Slf4j
@Service
public class MotionServiceImpl implements MotionService {

    @Value("${motion.websocket.url:ws://localhost:8081}")
    private String motionWebSocketUrl;

    @Value("${motion.websocket.timeout-ms:10000}")
    private long motionWebSocketTimeoutMs;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TaskService taskService;

    @Autowired
    private ScheduleService scheduleService;

    @Autowired
    private LogService logService;

    private WebSocketSession session;
    private final AtomicBoolean connected = new AtomicBoolean(false);
    private final AtomicLong messageCount = new AtomicLong(0);
    private volatile long lastConnectAttempt = 0;

    // 最近一次计算结果
    private volatile ForwardKinematicsResult lastForwardKinematics;
    private volatile InverseKinematicsResult lastInverseKinematics;

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
            log.warn("Motion 连接断开，尝试重连...");
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
            session = client.doHandshake(new MotionHandler(), new WebSocketHttpHeaders(), new URI(motionWebSocketUrl))
                    .get(10, TimeUnit.SECONDS);
            connected.set(true);
            log.info("Motion 算法端连接成功: {}", motionWebSocketUrl);
        } catch (Exception e) {
            log.error("Motion 算法端连接失败: {} - {}", motionWebSocketUrl, e.getMessage());
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

    private boolean ensureConnected() {
        if (connected.get() && session != null && session.isOpen()) {
            return true;
        }
        connect();
        return connected.get() && session != null && session.isOpen();
    }

    // ==================== 对外接口 ====================

    @Override
    public boolean sendTargetObject(String objName, double x, double y, double z) {
        if (!ensureConnected()) {
            log.warn("Motion 未连接，无法发送目标物体");
            return false;
        }

        try {
            Map<String, Object> msg = new LinkedHashMap<>();
            msg.put("obj_name", objName);
            msg.put("cx", x);
            msg.put("cy", y);
            msg.put("z", z);

            String payload = objectMapper.writeValueAsString(msg);
            session.sendMessage(new TextMessage(payload));
            log.info("发送目标物体至运动学算法端: obj_name={}, cxcy=({},{},{})", objName, x, y, z);

            // 清空上次结果，等待新报文
            lastForwardKinematics = null;
            lastInverseKinematics = null;
            return true;
        } catch (Exception e) {
            log.error("发送目标物体失败: {}", e.getMessage());
            return false;
        }
    }

    @Override
    public Map<String, Object> getConnectionStatus() {
        boolean isOpen = connected.get() && session != null && session.isOpen();
        Map<String, Object> status = new HashMap<>();
        status.put("connected", isOpen);
        status.put("url", motionWebSocketUrl);
        status.put("messageCount", messageCount.get());
        status.put("hasForwardResult", lastForwardKinematics != null);
        status.put("hasInverseResult", lastInverseKinematics != null);
        return status;
    }

    @Override
    public ForwardKinematicsResult getLastForwardKinematics() {
        return lastForwardKinematics;
    }

    @Override
    public InverseKinematicsResult getLastInverseKinematics() {
        return lastInverseKinematics;
    }

    // ==================== 消息处理 ====================

    private class MotionHandler extends TextWebSocketHandler {

        @Override
        protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
            String payload = message.getPayload();
            long count = messageCount.incrementAndGet();

            Map<String, Object> data;
            try {
                data = objectMapper.readValue(payload, new TypeReference<Map<String, Object>>() {
                });
            } catch (Exception e) {
                log.warn("解析 Motion 消息失败: {}", e.getMessage());
                return;
            }

            if (data.containsKey("target_pose")) {
                handleForwardKinematics(data);
            } else if (data.containsKey("ik_solve")) {
                handleInverseKinematics(data);
            } else {
                log.debug("收到未知格式 Motion 消息，忽略");
            }

            if (count == 1 || count % 100 == 0) {
                log.info("Motion 累计接收消息 {} 条", count);
            }
        }

        @Override
        public void handleTransportError(WebSocketSession session, Throwable exception) {
            log.error("Motion 传输错误: {}", exception.getMessage());
            connected.set(false);
        }

        @Override
        public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) {
            log.warn("Motion 连接关闭: {}", status);
            connected.set(false);
        }
    }

    // ==================== 报文解析与业务处理 ====================

    private void handleForwardKinematics(Map<String, Object> data) {
        try {
            ForwardKinematicsResult result = new ForwardKinematicsResult();
            result.setTargetObj(String.valueOf(data.getOrDefault("target_obj", "")));

            Object poseObj = data.get("target_pose");
            if (poseObj instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> poseMap = (Map<String, Object>) poseObj;
                ForwardKinematicsResult.TargetPose pose = new ForwardKinematicsResult.TargetPose();
                pose.setX(parseDouble(poseMap.get("x"), 0.0));
                pose.setY(parseDouble(poseMap.get("y"), 0.0));
                pose.setZ(parseDouble(poseMap.get("z"), 0.0));
                pose.setRoll(parseDouble(poseMap.get("roll"), 0.0));
                pose.setPitch(parseDouble(poseMap.get("pitch"), 0.0));
                pose.setYaw(parseDouble(poseMap.get("yaw"), 0.0));
                result.setTargetPose(pose);
            }

            lastForwardKinematics = result;
            ForwardKinematicsResult.TargetPose pose = result.getTargetPose();
            log.info("收到正运动学结果: target_obj={}, pose=({},{},{} / {},{},{})",
                    result.getTargetObj(),
                    pose != null ? pose.getX() : 0,
                    pose != null ? pose.getY() : 0,
                    pose != null ? pose.getZ() : 0,
                    pose != null ? pose.getRoll() : 0,
                    pose != null ? pose.getPitch() : 0,
                    pose != null ? pose.getYaw() : 0);
        } catch (Exception e) {
            log.error("处理正运动学报文失败", e);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleInverseKinematics(Map<String, Object> data) {
        try {
            InverseKinematicsResult result = new InverseKinematicsResult();
            result.setTargetObj(String.valueOf(data.getOrDefault("target_obj", "")));
            result.setIkSolve(Boolean.TRUE.equals(data.get("ik_solve")));
            result.setJointCount(parseInt(data.get("joint_count"), 0));

            Object jointValueObj = data.get("joint_value");
            List<Double> jointValues = new ArrayList<>();
            if (jointValueObj instanceof List) {
                for (Object item : (List<?>) jointValueObj) {
                    jointValues.add(parseDouble(item, 0.0));
                }
            }
            result.setJointValue(jointValues);

            lastInverseKinematics = result;
            log.info("收到逆运动学结果: target_obj={}, ik_solve={}, joints={}",
                    result.getTargetObj(), result.isIkSolve(), result.getJointValue());

            // 业务规则：ik_solve=true 时自动创建抓取任务
            if (result.isIkSolve()) {
                createGrabTask(result);
            } else {
                logService.createLog("SYSTEM",
                        "逆运动学无解: target_obj=" + result.getTargetObj(), null);
            }
        } catch (Exception e) {
            log.error("处理逆运动学报文失败", e);
        }
    }

    // ==================== 自动任务创建 ====================

    private void createGrabTask(InverseKinematicsResult ikResult) {
        try {
            ForwardKinematicsResult fkResult = lastForwardKinematics;
            if (fkResult == null || fkResult.getTargetPose() == null) {
                log.warn("逆运动学成功但正运动学结果缺失，无法创建抓取任务");
                return;
            }

            Map<String, Object> params = new LinkedHashMap<>();
            params.put("target_obj", ikResult.getTargetObj());
            params.put("target_pose", objectMapper.convertValue(fkResult.getTargetPose(), Map.class));
            params.put("joint_count", ikResult.getJointCount());
            params.put("joint_value", ikResult.getJointValue());

            Task task = new Task();
            task.setTaskName("抓取-" + ikResult.getTargetObj());
            task.setCommandType("GRAB");
            task.setPriority(1); // 抓取任务优先级较高
            task.setStatus(StatusConstant.TASK_STATUS_PENDING);
            task.setTaskParams(objectMapper.writeValueAsString(params));

            Task created = taskService.createTask(task);
            scheduleService.triggerSchedule();

            logService.createLog("TASK",
                    "逆运动学求解成功，自动创建抓取任务: " + created.getTaskId(), created.getTaskId());
            log.info("自动创建 GRAB 任务: taskId={}, target_obj={}",
                    created.getTaskId(), ikResult.getTargetObj());
        } catch (Exception e) {
            log.error("自动创建抓取任务失败", e);
        }
    }

    // ==================== 辅助方法 ====================

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
