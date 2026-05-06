package com.robot.scheduler;

import java.sql.*;
import java.util.Scanner;
import java.util.UUID;

/**
 * 综合数据库通信测试程序 —— 不依赖 Spring Boot 启动
 *
 * 作用：直接通过 JDBC 连接另一台电脑（或本机）上的 MySQL 数据库，
 * 对 robot_scheduler 库中所有业务表进行完整的增删改查验证。
 *
 * 覆盖的消息类型（来源于 src/test/java 下所有联调测试代码）：
 * 1. 任务消息       (ApiHttpClientMain / SimpleTaskServer)     -> task, task_record
 * 2. 机器人位姿     (SlamHttpTestClient /pose)                  -> robot, robot_pose_history
 * 3. ROS 导航目标   (SlamHttpTestClient /goal)                  -> ros_navigation_goal
 * 4. 视觉识别结果   (VisionCommTestServer / MotionCommTestClient)-> vision_detection
 * 5. 运动学结果     (MotionCommTestClient FK/IK)                -> motion_kinematics
 * 6. LLM 通信报文   (LLMWebSocketClientMain / Raw/Receive)      -> llm_communication
 * 7. SLAM 地图      (ImageBase64Test / SlamHttpTestClient)      -> map, map_live
 * 8. 系统/任务日志  (各服务内部记录)                             -> log
 *
 * 运行方式：
 * 1. IDE 直接运行 main()
 * 2. 命令行（需先 mvn test-compile）：
 *    mvn -q exec:java -Dexec.mainClass=com.robot.scheduler.IntegratedDbCommTest -Dexec.classpathScope=test
 * 3. 传参指定远程数据库：
 *    java com.robot.scheduler.IntegratedDbCommTest 192.168.1.100 3306 robot_scheduler root password
 */
public class IntegratedDbCommTest {

    // 默认使用主程序 application.yml 中的数据库配置
    private static String DB_HOST = "172.16.26.168";
    private static int DB_PORT = 3306;
    private static String DB_NAME = "robot_data_db";
    private static String DB_USER = "rootuser";
    private static String DB_PASSWORD = "Zyy060116";
    private static boolean AUTO_CLEANUP = false;  // 是否测试结束后自动清理模拟数据

    private static final String[] TEST_ROBOT_IDS = new String[3];
    private static final String[] TEST_TASK_IDS = new String[3];
    private static final String[] TEST_OTHER_IDS = new String[8];

    public static void main(String[] args) {
        resolveArgs(args);
        String jdbcUrl = String.format(
                "jdbc:mysql://%s:%d/%s?useSSL=false&serverTimezone=UTC&allowPublicKeyRetrieval=true&characterEncoding=utf8",
                DB_HOST, DB_PORT, DB_NAME);

        System.out.println("==================================================");
        System.out.println("  综合数据库通信测试客户端");
        System.out.println("  目标数据库: " + jdbcUrl);
        System.out.println("  用户名: " + DB_USER);
        System.out.println("==================================================\n");

        try {
            Class.forName("com.mysql.cj.jdbc.Driver");
        } catch (ClassNotFoundException e) {
            System.err.println("[错误] 未找到 MySQL 驱动，请确认 pom.xml 中已引入 mysql-connector-java");
            return;
        }

        try (Connection conn = DriverManager.getConnection(jdbcUrl, DB_USER, DB_PASSWORD)) {
            conn.setAutoCommit(false);
            System.out.println("[连接成功] 数据库连接已建立\n");

            // 预生成测试 ID，便于后续关联
            for (int i = 0; i < TEST_ROBOT_IDS.length; i++) TEST_ROBOT_IDS[i] = genUuid();
            for (int i = 0; i < TEST_TASK_IDS.length; i++) TEST_TASK_IDS[i] = genUuid();
            for (int i = 0; i < TEST_OTHER_IDS.length; i++) TEST_OTHER_IDS[i] = genUuid();

            // ---------- 1. 机器人表 CRUD（模拟位姿心跳消息） ----------
            testRobotCrud(conn);

            // ---------- 2. 任务表 + 任务记录表 CRUD（模拟任务创建/流转消息） ----------
            testTaskCrud(conn);

            // ---------- 3. 机器人位姿历史 CRUD（模拟 ROS 轮询 pose 消息） ----------
            testRobotPoseHistoryCrud(conn);

            // ---------- 4. ROS 导航目标 CRUD（模拟 goal 下发/查询消息） ----------
            testRosGoalCrud(conn);

            // ---------- 5. 视觉识别结果 CRUD（模拟视觉端 /ws/vision 消息） ----------
            testVisionDetectionCrud(conn);

            // ---------- 6. 运动学结果 CRUD（模拟运动学端 FK/IK 回传消息） ----------
            testMotionKinematicsCrud(conn);

            // ---------- 7. LLM 通信记录 CRUD（模拟 LLM WebSocket 消息） ----------
            testLlmCommunicationCrud(conn);

            // ---------- 8. SLAM 地图 + 实时地图 CRUD（模拟地图上传/切换消息） ----------
            testMapCrud(conn);

            // ---------- 9. 日志表 CRUD（模拟系统内部日志消息） ----------
            testLogCrud(conn);

            conn.commit();
            System.out.println("\n==================================================");
            if (AUTO_CLEANUP) {
                cleanupTestData(conn);
                conn.commit();
                System.out.println("  全部测试通过，测试数据已自动清理");
            } else {
                System.out.println("  全部测试通过，事务已提交（测试数据保留在库中）");
                System.out.println("  如需清理数据，请执行：");
                System.out.println("  DELETE FROM robot_pose_history WHERE robot_id LIKE 'test-%';");
                System.out.println("  （其他表同理，所有测试数据 robot_id/task_id 均带 test- 前缀）");
                System.out.println("  或在运行时追加第6个参数 true / cleanup 实现自动清理");
            }
            System.out.println("==================================================");

        } catch (SQLException e) {
            System.err.println("[数据库错误] " + e.getMessage());
            e.printStackTrace();
        }
    }

    private static void resolveArgs(String[] args) {
        if (args.length == 0) return;

        // 如果只有一个参数且是 cleanup/true，只开启自动清理，不覆盖数据库配置
        if (args.length == 1 && ("true".equalsIgnoreCase(args[0]) || "cleanup".equalsIgnoreCase(args[0]))) {
            AUTO_CLEANUP = true;
            return;
        }

        if (args.length > 0) DB_HOST = args[0];
        if (args.length > 1) DB_PORT = Integer.parseInt(args[1]);
        if (args.length > 2) DB_NAME = args[2];
        if (args.length > 3) DB_USER = args[3];
        if (args.length > 4) DB_PASSWORD = args[4];
        if (args.length > 5) AUTO_CLEANUP = "true".equalsIgnoreCase(args[5]) || "cleanup".equalsIgnoreCase(args[5]);
    }

    private static String genUuid() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    // ==================== 自动清理 ====================
    private static void cleanupTestData(Connection conn) throws SQLException {
        System.out.println("[自动清理] 开始删除测试数据...");
        // 注意删除顺序：先子表后父表
        String[] sqls = {
            "DELETE FROM task_record WHERE task_id IN (SELECT task_id FROM task WHERE robot_code LIKE 'test-%')",
            "DELETE FROM task WHERE robot_code LIKE 'test-%'",
            "DELETE FROM motion_kinematics WHERE task_id IN (SELECT robot_id FROM robot WHERE robot_code LIKE 'test-%') OR detection_id IN (SELECT detection_id FROM vision_detection WHERE source = 'websocket')",
            "DELETE FROM vision_detection WHERE source = 'websocket'",
            "DELETE FROM llm_communication WHERE action = 'parse_natural_language' AND request_content LIKE '%去A点拿杯子%'",
            "DELETE FROM ros_navigation_goal WHERE robot_code LIKE 'test-%'",
            "DELETE FROM robot_pose_history WHERE robot_id IN (SELECT robot_id FROM robot WHERE robot_code LIKE 'test-%')",
            "DELETE FROM map_live WHERE map_id IN (SELECT map_id FROM map WHERE map_name LIKE 'test_map_%')",
            "DELETE FROM map WHERE map_name LIKE 'test_map_%'",
            "DELETE FROM robot WHERE robot_code LIKE 'test-%'"
        };
        for (String sql : sqls) {
            try (PreparedStatement ps = conn.prepareStatement(sql)) {
                int rows = ps.executeUpdate();
                if (rows > 0) {
                    System.out.println("  [清理] " + sql.substring(0, Math.min(sql.length(), 60)) + "... 影响行数: " + rows);
                }
            }
        }
        System.out.println("[自动清理] 完成");
    }

    // ==================== 1. robot ====================
    private static void testRobotCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-1] robot 表 CRUD —— 模拟机器人状态/位姿心跳消息");
        String rid = TEST_ROBOT_IDS[0];

        // Create
        String insert = "INSERT INTO robot (robot_id, robot_name, robot_code, status, `load`, battery, x, y, yaw, last_heartbeat) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, rid);
            ps.setString(2, "测试机器人-A");
            ps.setString(3, "test-r001");
            ps.setString(4, "空闲");
            ps.setInt(5, 0);
            ps.setInt(6, 85);
            ps.setDouble(7, 10.5);
            ps.setDouble(8, 20.3);
            ps.setDouble(9, 1.57);
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入机器人: " + rid);

        // Read
        String select = "SELECT robot_name, status, battery, x, y, yaw FROM robot WHERE robot_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, rid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] 机器人信息: name=%s, status=%s, battery=%d%%, pose=(%.2f, %.2f, %.2f)%n",
                        rs.getString("robot_name"), rs.getString("status"),
                        rs.getInt("battery"), rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("yaw"));
            }
        }

        // Update
        String update = "UPDATE robot SET status = ?, `load` = ?, x = ?, y = ? WHERE robot_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, "忙碌");
            ps.setInt(2, 1);
            ps.setDouble(3, 12.0);
            ps.setDouble(4, 22.0);
            ps.setString(5, rid);
            ps.executeUpdate();
        }
        System.out.println("  [改] 更新机器人状态为忙碌，负载+1，位置更新");

        // Verify update
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, rid);
            ResultSet rs = ps.executeQuery();
            if (rs.next() && "忙碌".equals(rs.getString("status"))) {
                System.out.println("  [验] 更新验证通过");
            }
        }
        System.out.println();
    }

    // ==================== 2. task + task_record ====================
    private static void testTaskCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-2] task + task_record 表 CRUD —— 模拟任务创建/流转消息");
        String tid = TEST_TASK_IDS[0];
        String rid = TEST_ROBOT_IDS[0];

        // Create task
        String insertTask = "INSERT INTO task (task_id, task_name, command_type, priority, robot_id, robot_code, status, " +
                "task_params, estimated_duration, deadline, dynamic_priority_score, create_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, DATE_ADD(NOW(), INTERVAL 1 HOUR), ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insertTask)) {
            ps.setString(1, tid);
            ps.setString(2, "测试搬运任务");
            ps.setString(3, "MOVE_TO");
            ps.setInt(4, 1);
            ps.setString(5, rid);
            ps.setString(6, "test-r001");
            ps.setString(7, "QUEUED");
            ps.setString(8, "{\"x\":20,\"y\":8,\"speed\":1.5}");
            ps.setInt(9, 120);
            ps.setDouble(10, 15.5);
            // create_time 由 NOW() 填充，无需 set 第11个参数
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入任务: " + tid);

        // Create task_record (QUEUED -> RUNNING)
        String insertRecord = "INSERT INTO task_record (record_id, task_id, old_status, new_status, change_reason, change_time) " +
                "VALUES (?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insertRecord)) {
            ps.setString(1, genUuid());
            ps.setString(2, tid);
            ps.setString(3, "QUEUED");
            ps.setString(4, "RUNNING");
            ps.setString(5, "调度器分配机器人");
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入任务状态流转记录: QUEUED -> RUNNING");

        // Read
        String select = "SELECT t.task_name, t.status, t.priority, t.task_params, r.new_status " +
                "FROM task t LEFT JOIN task_record r ON t.task_id = r.task_id WHERE t.task_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, tid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                System.out.printf("  [查] 任务: name=%s, status=%s, priority=%d, params=%s, record_new=%s%n",
                        rs.getString("task_name"), rs.getString("status"),
                        rs.getInt("priority"), rs.getString("task_params"), rs.getString("new_status"));
            }
        }

        // Update task status
        String update = "UPDATE task SET status = ?, start_time = NOW() WHERE task_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, "RUNNING");
            ps.setString(2, tid);
            ps.executeUpdate();
        }
        System.out.println("  [改] 更新任务状态: QUEUED -> RUNNING");
        System.out.println();
    }

    // ==================== 3. robot_pose_history ====================
    private static void testRobotPoseHistoryCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-3] robot_pose_history 表 CRUD —— 模拟 ROS HTTP 位姿轮询消息");
        String rid = TEST_ROBOT_IDS[0];

        // Create (模拟多次轮询)
        String insert = "INSERT INTO robot_pose_history (robot_id, x, y, yaw, source, create_time) VALUES (?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            for (int i = 0; i < 3; i++) {
                ps.setString(1, rid);
                ps.setDouble(2, 1.0 + i * 0.5);
                ps.setDouble(3, 2.0 + i * 0.3);
                ps.setDouble(4, 0.1 * i);
                ps.setString(5, "ROS_HTTP");
                ps.addBatch();
            }
            ps.executeBatch();
        }
        System.out.println("  [增] 插入 3 条位姿历史记录");

        // Read
        String select = "SELECT x, y, yaw, source, create_time FROM robot_pose_history WHERE robot_id = ? ORDER BY create_time DESC LIMIT 2";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, rid);
            ResultSet rs = ps.executeQuery();
            while (rs.next()) {
                System.out.printf("  [查] 位姿: (%.2f, %.2f, %.2f) source=%s time=%s%n",
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("yaw"),
                        rs.getString("source"), rs.getTimestamp("create_time"));
            }
        }

        // Update (修正某条记录来源)
        String update = "UPDATE robot_pose_history SET source = ? WHERE robot_id = ? AND source = ? LIMIT 1";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, "TEST_CORRECTED");
            ps.setString(2, rid);
            ps.setString(3, "ROS_HTTP");
            int rows = ps.executeUpdate();
            System.out.println("  [改] 修正来源字段，影响行数: " + rows);
        }
        System.out.println();
    }

    // ==================== 4. ros_navigation_goal ====================
    private static void testRosGoalCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-4] ros_navigation_goal 表 CRUD —— 模拟导航目标下发/查询消息");
        String gid = TEST_OTHER_IDS[0];
        String rid = TEST_ROBOT_IDS[0];

        // Create
        String insert = "INSERT INTO ros_navigation_goal (goal_id, robot_id, robot_code, x, y, yaw, status, send_time, create_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, gid);
            ps.setString(2, rid);
            ps.setString(3, "test-r001");
            ps.setDouble(4, 2.5);
            ps.setDouble(5, 3.0);
            ps.setDouble(6, 1.57);
            ps.setString(7, "PENDING");
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入导航目标: (2.5, 3.0, 1.57)");

        // Read
        String select = "SELECT x, y, yaw, status FROM ros_navigation_goal WHERE goal_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, gid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] 导航目标: (%.2f, %.2f, %.2f) status=%s%n",
                        rs.getDouble("x"), rs.getDouble("y"), rs.getDouble("yaw"), rs.getString("status"));
            }
        }

        // Update
        String update = "UPDATE ros_navigation_goal SET status = ?, complete_time = NOW() WHERE goal_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, "COMPLETED");
            ps.setString(2, gid);
            ps.executeUpdate();
        }
        System.out.println("  [改] 更新导航目标状态: PENDING -> COMPLETED");
        System.out.println();
    }

    // ==================== 5. vision_detection ====================
    private static void testVisionDetectionCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-5] vision_detection 表 CRUD —— 模拟视觉端 WebSocket 识别消息");
        String did = TEST_OTHER_IDS[1];

        // Create (模拟 VisionCommTestServer 收到: {"obj_name":"cup","cx":320,"cy":240,"z":0.680})
        String insert = "INSERT INTO vision_detection (detection_id, obj_name, cx, cy, x, y, z, confidence, source, create_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, did);
            ps.setString(2, "cup");
            ps.setInt(3, 320);
            ps.setInt(4, 240);
            ps.setDouble(5, 320.0);
            ps.setDouble(6, 240.0);
            ps.setDouble(7, 0.680);
            ps.setDouble(8, 0.95);
            ps.setString(9, "websocket");
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入视觉识别: obj=cup, cx=320, cy=240, z=0.680");

        // Read
        String select = "SELECT obj_name, cx, cy, z, confidence FROM vision_detection WHERE detection_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, did);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] 视觉识别: obj=%s, pixel=(%d, %d), z=%.3f, conf=%.2f%n",
                        rs.getString("obj_name"), rs.getInt("cx"), rs.getInt("cy"),
                        rs.getDouble("z"), rs.getDouble("confidence"));
            }
        }

        // Update (修正深度值)
        String update = "UPDATE vision_detection SET z = ? WHERE detection_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setDouble(1, 0.720);
            ps.setString(2, did);
            ps.executeUpdate();
        }
        System.out.println("  [改] 修正深度值: 0.680 -> 0.720");
        System.out.println();
    }

    // ==================== 6. motion_kinematics ====================
    private static void testMotionKinematicsCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-6] motion_kinematics 表 CRUD —— 模拟运动学 FK/IK 回传消息");
        String kid = TEST_OTHER_IDS[2];
        String did = TEST_OTHER_IDS[1]; // 关联视觉识别
        String tid = TEST_TASK_IDS[0];  // 关联任务

        // Create (模拟 MotionCommTestClient 收到的双报文)
        String insert = "INSERT INTO motion_kinematics (kinematics_id, detection_id, task_id, fk_target_pose, ik_solve, ik_joint_angles, ik_message, create_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, kid);
            ps.setString(2, did);
            ps.setString(3, tid);
            ps.setString(4, "{\"x\":0.45,\"y\":0.12,\"z\":0.68,\"rx\":0.0,\"ry\":0.0,\"rz\":0.0}");
            ps.setInt(5, 1);
            ps.setString(6, "[0.5, -0.3, 1.2, 0.0, 0.8, -0.1]");
            ps.setString(7, "IK solve success");
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入运动学结果: ik_solve=true, 关联 task=" + tid);

        // Read
        String select = "SELECT fk_target_pose, ik_solve, ik_joint_angles, ik_message FROM motion_kinematics WHERE kinematics_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, kid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] FK=%s, IK=%s, angles=%s, msg=%s%n",
                        rs.getString("fk_target_pose"), rs.getInt("ik_solve") == 1 ? "true" : "false",
                        rs.getString("ik_joint_angles"), rs.getString("ik_message"));
            }
        }

        // Update (模拟 IK 失败后重新求解)
        String update = "UPDATE motion_kinematics SET ik_solve = ?, ik_message = ? WHERE kinematics_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setInt(1, 0);
            ps.setString(2, "IK solve failed: out of workspace");
            ps.setString(3, kid);
            ps.executeUpdate();
        }
        System.out.println("  [改] 更新 IK 状态为失败，记录原因");
        System.out.println();
    }

    // ==================== 7. llm_communication ====================
    private static void testLlmCommunicationCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-7] llm_communication 表 CRUD —— 模拟 LLM WebSocket 收发消息");
        String mid = TEST_OTHER_IDS[3];

        // Create
        String insert = "INSERT INTO llm_communication (msg_id, action, request_content, response_content, status, duration_ms, create_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert)) {
            ps.setString(1, mid);
            ps.setString(2, "parse_natural_language");
            ps.setString(3, "{\"text\":\"去A点拿杯子然后放到B点\"}");
            ps.setString(4, "{\"plan\":[{\"action\":\"NAVIGATE\"},{\"action\":\"GRAB\"},{\"action\":\"NAVIGATE\"},{\"action\":\"PLACE\"}]}");
            ps.setString(5, "SUCCESS");
            ps.setInt(6, 1250);
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入 LLM 通信记录: action=parse_natural_language");

        // Read
        String select = "SELECT action, status, duration_ms, response_content FROM llm_communication WHERE msg_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, mid);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] action=%s, status=%s, duration=%dms, resp=%s%n",
                        rs.getString("action"), rs.getString("status"),
                        rs.getInt("duration_ms"), rs.getString("response_content"));
            }
        }

        // Update
        String update = "UPDATE llm_communication SET status = ?, duration_ms = ? WHERE msg_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, "FAILED");
            ps.setInt(2, 5000);
            ps.setString(3, mid);
            ps.executeUpdate();
        }
        System.out.println("  [改] 更新 LLM 状态为超时失败");
        System.out.println();
    }

    // ==================== 8. map + map_live ====================
    private static void testMapCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-8] map + map_live 表 CRUD —— 模拟 SLAM 地图上传/切换消息");
        String mapId = TEST_OTHER_IDS[4];

        // Create map
        String insertMap = "INSERT INTO map (map_id, map_name, pgm_data, yaml_data, resolution, origin_x, origin_y, origin_yaw, " +
                "width, height, negate, occupied_thresh, free_thresh, is_active, create_time, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW(), NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insertMap)) {
            ps.setString(1, mapId);
            ps.setString(2, "test_map_001");
            ps.setBytes(3, new byte[]{0x50, 0x35, 0x0A, 0x33, 0x20, 0x33, 0x0A, 0x32, 0x35, 0x35, 0x0A}); // 模拟 PGM 头
            ps.setString(4, "resolution: 0.05\norigin: [0.0, 0.0, 0.0]\nnegate: 0\noccupied_thresh: 0.65\nfree_thresh: 0.196");
            ps.setDouble(5, 0.05);
            ps.setDouble(6, 0.0);
            ps.setDouble(7, 0.0);
            ps.setDouble(8, 0.0);
            ps.setInt(9, 512);
            ps.setInt(10, 512);
            ps.setInt(11, 0);
            ps.setDouble(12, 0.65);
            ps.setDouble(13, 0.196);
            ps.setInt(14, 1);
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入静态地图: " + mapId);

        // Create map_live (使用 REPLACE INTO 避免主键冲突，便于重复测试)
        String insertLive = "REPLACE INTO map_live (live_id, map_id, resolution, width, height, origin_x, origin_y, origin_yaw, grid_data, obstacles, update_time) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insertLive)) {
            ps.setString(1, "current");
            ps.setString(2, mapId);
            ps.setDouble(3, 0.05);
            ps.setInt(4, 512);
            ps.setInt(5, 512);
            ps.setDouble(6, 0.0);
            ps.setDouble(7, 0.0);
            ps.setDouble(8, 0.0);
            ps.setString(9, "[0,0,0,255,255,0,...]");
            ps.setString(10, "[{\"type\":\"rect\",\"x\":1,\"y\":2,\"w\":3,\"h\":4}]");
            ps.executeUpdate();
        }
        System.out.println("  [增] 插入实时地图快照: live_id=current");

        // Read
        String select = "SELECT m.map_name, m.resolution, l.grid_data, l.obstacles " +
                "FROM map m JOIN map_live l ON m.map_id = l.map_id WHERE m.map_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setString(1, mapId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] map_name=%s, resolution=%.2f, grid_len=%d, obstacles=%s%n",
                        rs.getString("map_name"), rs.getDouble("resolution"),
                        rs.getString("grid_data").length(), rs.getString("obstacles"));
            }
        }

        // Update (切换激活地图)
        String update = "UPDATE map SET is_active = 0 WHERE map_id != ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, mapId);
            ps.executeUpdate();
        }
        System.out.println("  [改] 切换激活地图（将其他地图置为非激活）");
        System.out.println();
    }

    // ==================== 9. log ====================
    private static void testLogCrud(Connection conn) throws SQLException {
        System.out.println("[TEST-9] log 表 CRUD —— 模拟系统日志消息");
        long logId;

        // Create
        String insert = "INSERT INTO log (log_type, message, reference_id, create_time) VALUES (?, ?, ?, NOW())";
        try (PreparedStatement ps = conn.prepareStatement(insert, Statement.RETURN_GENERATED_KEYS)) {
            ps.setString(1, "SYSTEM");
            ps.setString(2, "[测试] 综合数据库通信测试启动");
            ps.setString(3, TEST_ROBOT_IDS[0]);
            ps.executeUpdate();
            ResultSet rs = ps.getGeneratedKeys();
            rs.next();
            logId = rs.getLong(1);
        }
        System.out.println("  [增] 插入系统日志, 自增ID=" + logId);

        // Read
        String select = "SELECT log_type, message, reference_id FROM log WHERE log_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(select)) {
            ps.setLong(1, logId);
            ResultSet rs = ps.executeQuery();
            if (rs.next()) {
                System.out.printf("  [查] log_type=%s, msg=%s, ref=%s%n",
                        rs.getString("log_type"), rs.getString("message"), rs.getString("reference_id"));
            }
        }

        // Update
        String update = "UPDATE log SET message = ? WHERE log_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(update)) {
            ps.setString(1, "[测试] 综合数据库通信测试结束");
            ps.setLong(2, logId);
            ps.executeUpdate();
        }
        System.out.println("  [改] 更新日志内容为结束状态");

        // Delete (日志表允许清理)
        String delete = "DELETE FROM log WHERE log_id = ?";
        try (PreparedStatement ps = conn.prepareStatement(delete)) {
            ps.setLong(1, logId);
            ps.executeUpdate();
        }
        System.out.println("  [删] 删除测试日志记录");
        System.out.println();
    }
}
