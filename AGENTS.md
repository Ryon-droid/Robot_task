# AGENTS.md — Robot Scheduler 机器人调度系统

> 本文件面向 AI Coding Agent。项目的主要自然语言为中文（注释、文档、状态常量等）。

---

## 1. 项目概述

Robot Scheduler 是一个基于 **Spring Boot 2.7.18** 的机器人任务调度后端服务，负责：
- 多机器人任务分配与状态跟踪
- 任务生命周期管理（创建 → 排队 → 执行 → 完成/失败）
- 机器人实时位姿管理与目标点下发
- 调度日志记录
- 与外部 LLM 服务（WebSocket）对接，支持自然语言指令解析与行为树
- SLAM 地图管理：支持 ROS 标准 `.pgm` + `.yaml` 格式解析，MySQL 持久化存储（pgm 存 LONGBLOB，yaml 存 TEXT），支持多地图切换
- ROS2 通信对接：通过 HTTP REST API 与 SLAM 端（http_scheduler_bridge.py）对接，轮询位姿、下发导航目标
- 视觉-运动学算法对接：接收视觉识别结果并转发给运动学算法端，逆运动学求解成功后自动创建抓取任务
- 动态优先级调度：基于等待时间、截止时间、任务类型、机器人匹配度等 5 维因子自动重算优先级

项目无单元测试/集成测试类，但 `src/test/java` 下包含若干**通信测试客户端/服务端**（用于与外部视觉、运动学、LLM 服务联调），`pom.xml` 已引入 `spring-boot-starter-test` 与 `java-websocket`（test scope）。

---

## 2. 技术栈与运行时架构

| 组件 | 技术/版本 |
|------|-----------|
| 语言 / JDK | Java 17 |
| 构建工具 | Maven 3.x |
| 主框架 | Spring Boot 2.7.18 |
| Web | Spring Boot Starter Web |
| WebSocket | Spring Boot Starter WebSocket |
| ORM | MyBatis-Plus 3.5.3.1 |
| 数据库 | MySQL 8.0 (`mysql-connector-java` 8.0.33) |
| JSON | Jackson Databind |
| 本地开发数据库 | H2（runtime scope，`application-local.yml`） |
| 工具类 | Lombok |
| 测试辅助 | Java-WebSocket 1.5.3（test scope，联调客户端/服务端） |

### 启动入口
- `com.robot.scheduler.SchedulerApplication`
- 注解：`@SpringBootApplication`、`@MapperScan("com.robot.scheduler.mapper")`、`@EnableScheduling`

### 运行方式
```bash
# 1. 在 MySQL 中执行 db_init.sql 创建数据库与表
# 2. 按需修改 src/main/resources/application.yml 中的数据库连接信息
# 3. 启动
mvn spring-boot:run

# 本地开发（H2 内存数据库，无需 MySQL）
mvn spring-boot:run -Dspring-boot.run.profiles=local
```
- 默认端口：`8080`
- 数据库名：`robot_scheduler`（`application.yml` 中当前实际配置为 `robot_data_db`，可按需修改）

---

## 3. 项目结构

```
robot-scheduler/
├── pom.xml                                # Maven 配置
├── db_init.sql                            # 数据库初始化脚本
├── src/main/java/com/robot/scheduler/
│   ├── SchedulerApplication.java          # 启动类
│   ├── common/                            # 公共工具/异常/响应封装
│   │   ├── Result.java                    # 统一响应体（code/message/data）
│   │   ├── StatusConstant.java            # 状态常量（机器人中文 / 任务英文）
│   │   ├── BusinessException.java         # 业务异常（携带 code）
│   │   └── GlobalExceptionHandler.java    # 全局异常拦截
│   ├── config/
│   │   ├── CorsConfig.java                # 跨域配置（全开放）
│   │   ├── AsyncConfig.java               # 异步线程池配置（数据上报）
│   │   └── WebSocketServerConfig.java     # WebSocket 服务端配置（接收视觉识别数据）
│   ├── controller/                        # REST API 层
│   │   ├── NewTaskController.java         # 任务管理 API  (/api/v1/tasks)
│   │   ├── RobotController.java           # 机器人前端 API (/api/robots ...)
│   │   ├── LogController.java             # 日志查询 API   (/api/v1/logs)
│   │   ├── SchedulerExternalController.java # 外部数据服务 API (/scheduler/*)
│   │   ├── LLMController.java             # LLM 交互      (/api/v1/scheduler/llm/*)
│   │   ├── SLAMController.java            # SLAM 地图管理  (/api/v1/scheduler/slam/*)
│   │   ├── RosBridgeController.java       # ROS2 通信状态与导航目标 (/api/v1/scheduler/ros/*)
│   │   └── MotionController.java          # 视觉-运动学算法通信 (/api/v1/motion/*)
│   ├── dto/
│   │   ├── TaskRankingDTO.java            # 任务排行精简字段 DTO（前端专用）
│   │   ├── ForwardKinematicsResult.java   # 正运动学末端位姿结果
│   │   ├── InverseKinematicsResult.java   # 逆运动学求解结果
│   │   └── MotionTargetRequest.java       # 发送给运动学算法端的目标物体请求
│   ├── entity/                            # 实体类（与数据库表对应）
│   │   ├── Task.java                      # 任务实体（实现 Comparable，按优先级+创建时间排序）
│   │   ├── Robot.java                     # 机器人实体
│   │   ├── Log.java                       # 日志实体
│   │   ├── TaskRecord.java                # 任务状态变更记录
│   │   ├── MapInfo.java                   # SLAM 静态地图实体（pgm/yaml 元数据）
│   │   └── MapLive.java                   # SLAM 实时地图快照实体
│   ├── mapper/                            # MyBatis-Plus Mapper 接口
│   │   ├── TaskMapper.java
│   │   ├── RobotMapper.java
│   │   ├── LogMapper.java
│   │   ├── TaskRecordMapper.java
│   │   ├── MapInfoMapper.java
│   │   └── MapLiveMapper.java
│   ├── service/                           # 服务接口
│   │   ├── TaskService.java
│   │   ├── RobotService.java
│   │   ├── LogService.java
│   │   ├── ScheduleService.java           # 核心调度接口
│   │   ├── TaskPriorityPlanner.java       # 动态优先级计算接口
│   │   ├── DataServiceClient.java         # 数据服务 HTTP 上报客户端（@Component 类）
│   │   ├── LLMService.java
│   │   ├── MotionService.java             # 视觉-运动学算法 WebSocket 通信接口
│   │   ├── SLAMService.java
│   │   ├── RosBridgeService.java          # ROS2 HTTP 通信接口
│   │   └── StateTrackService.java
│   └── service/impl/                      # 服务实现
│       ├── TaskServiceImpl.java           # 任务增删改查、取消、重分配、优先级调整
│       ├── RobotServiceImpl.java          # 机器人列表、目标点、位姿更新、紧急停止
│       ├── LogServiceImpl.java
│       ├── ScheduleServiceImpl.java       # 核心调度逻辑（优先级队列 + 乐观锁 + 动态优先级刷新）
│       ├── TaskPriorityPlannerImpl.java   # 5 维动态优先级评分（基础/等待/截止/类型/机器人匹配）
│       ├── LLMServiceImpl.java            # 通过 WebSocket 调用外部 LLM
│       ├── SLAMServiceImpl.java           # SLAM 地图解析（P5 PGM + YAML）、多地图管理、A* 路径规划
│       ├── RosBridgeServiceImpl.java      # ROS2 HTTP 客户端（GET 轮询位姿、POST 导航目标）
│       ├── StateTrackServiceImpl.java     # 状态变更记录与机器人状态更新
│       ├── MotionServiceImpl.java         # 运动学算法 WebSocket 客户端（发送目标物体、接收正/逆运动学结果）
│       └── VisionWebSocketHandler.java    # 视觉识别 WebSocket 服务端处理器（端点 /ws/vision）
└── src/main/resources/
    ├── application.yml                    # Spring Boot 配置
    └── mapper/                            # 预留 XML Mapper 目录（当前无文件）
├── src/test/java/                         # 测试与联调工具（非单元测试）
│   ├── MotionCommTestClient.java          # 运动学算法端 WebSocket 测试客户端
│   ├── IntegratedDbCommTest.java          # 综合数据库通信测试（JDBC 直连所有业务表）
│   └── com/robot/scheduler/
│       ├── VisionCommTestServer.java      # 视觉识别端 WebSocket 测试服务端
│       ├── VisionMotionBridgeTest.java    # 视觉-运动学桥接测试（同时作服务端+客户端）
│       ├── SlamHttpTestClient.java        # SLAM HTTP 测试客户端
│       ├── ApiHttpClientMain.java         # HTTP API 测试客户端
│       ├── ImageBase64Test.java           # 图片 Base64 编码测试
│       ├── SimpleTaskServer.java          # 简易任务模拟服务端
│       └── service/impl/
│           ├── LLMWebSocketClientMain.java
│           ├── LLMWebSocketRawConnectionTest.java
│           └── LLMWebSocketReceiveTest.java
├── realsense_yolo_csv.py                  # 外部视觉识别脚本（RealSense + YOLOv8）
```

---

## 4. 数据库设计

执行 `db_init.sql` 初始化：

- **`robot`** — 机器人信息（robot_id, robot_name, robot_code, status, load, battery, x, y, yaw, last_heartbeat）
- **`task`** — 任务信息（task_id, task_name, command_type, priority, robot_id, robot_code, status, task_params(JSON), 时间戳, fail_reason, deadline, estimated_duration, dynamic_priority_score）
- **`task_record`** — 任务状态流转记录（record_id, task_id, old_status, new_status, change_time, change_reason）
- **`log`** — 系统/任务/机器人日志（log_id, log_type, message, reference_id, create_time）
- **`map`** — SLAM 静态地图信息（map_id, map_name, pgm_data, yaml_data, resolution, origin_x/y/yaw, width, height, negate, occupied_thresh, free_thresh, is_active）
- **`map_live`** — SLAM 实时地图快照（live_id='current', map_id, resolution, width, height, origin_x/y/yaw, grid_data(JSON), obstacles(JSON), update_time）
- **`vision_detection`** — 视觉识别结果（代码中暂无实体类，供联调测试预留）
- **`motion_kinematics`** — 运动学正逆解结果（代码中暂无实体类，供联调测试预留）
- **`llm_communication`** — LLM 通信记录（代码中暂无实体类，供联调测试预留）
- **`ros_navigation_goal`** — ROS 导航目标记录（代码中暂无实体类，供联调测试预留）
- **`robot_pose_history`** — 机器人位姿历史（代码中暂无实体类，供联调测试预留）

> 主键字段在代码中统一生成无横线 UUID（32 位），数据库定义为 `VARCHAR(36)` 以兼容标准 UUID 长度。

### 数据源配置（application.yml 示例）
```yaml
spring:
  datasource:
    url: jdbc:mysql://localhost:3306/robot_scheduler?useSSL=false&serverTimezone=UTC
    username: root
    password: password
    driver-class-name: com.mysql.cj.jdbc.Driver

mybatis-plus:
  mapper-locations: classpath:mapper/*.xml
  configuration:
    map-underscore-to-camel-case: true
```

---

## 5. 代码组织与分层约定

### 5.1 响应格式
所有 Controller 返回统一的 `Result<T>`：
```java
Result.success(data);          // code=200, message="操作成功"
Result.error(message);         // code=500
Result.error(code, message);   // 自定义
```

### 5.2 异常处理
- 业务异常使用 `BusinessException(int code, String message)`，由 `GlobalExceptionHandler` 自动捕获并包装为 `Result`。
- 未捕获异常返回 `code=500`，`message` 为异常信息或 `"系统异常"`。

### 5.3 实体规范
- 使用 Lombok `@Data` 生成 getter/setter。
- 使用 MyBatis-Plus 注解：
  - `@TableName("表名")`
  - `@TableId(value = "主键列名", type = IdType.INPUT)`（除 log 表使用 `IdType.AUTO` 外）
- 主键生成：代码中统一使用 `UUID.randomUUID().toString().replace("-", "")`（32 位无横线）。

### 5.4 Service 层
- 接口定义在 `service/` 包，实现类在 `service/impl/` 包。
- 实现类标注 `@Service`。
- 涉及多表更新或状态变更的方法使用 `@Transactional`。

### 5.5 Mapper 层
- 全部继承 `BaseMapper<T>`，无自定义 XML。
- 复杂查询使用 MyBatis-Plus `QueryWrapper` / `UpdateWrapper`。

---

## 6. 核心功能与业务规则

### 6.1 任务调度（ScheduleServiceImpl）
- 内部维护一个 `PriorityBlockingQueue<Task>`，按 **`dynamic_priority_score` 升序**（越低越优先），再按 **`createTime` 升序**。
- 启动时从数据库加载 `status='QUEUED'` 的任务到队列（上限 1000）。
- `triggerSchedule()` 被调用时（如创建任务后、机器人回调完成后、手动触发），尝试为队列中的任务分配空闲机器人。
- 调度过程使用 `ReentrantLock.tryLock()` 防止竞态条件。
- **乐观锁分配流程**：
  1. 查询 `status='空闲'` 且 `load` 最小的机器人；
  2. 用 `UpdateWrapper` 条件更新任务 `status='QUEUED' → 'RUNNING'`；
  3. 用 `UpdateWrapper` 条件更新机器人 `status='空闲' → '忙碌'` 并 `load+1`；
  4. 任意一步失败则回滚或跳过。
- 机器人故障时，将其正在执行的任务状态回退为 `"QUEUED"` 并重新入队，机器人状态设为 `"故障"`。

### 6.2 任务状态流转
- 全系统统一使用 `StatusConstant` 中的英文状态常量：
  - **`QUEUED` → `RUNNING` → `SUCCESS` / `FAILED`**
- 更新为 `SUCCESS` 或 `FAILED` 时，自动写 `log` 表记录。

### 6.3 机器人管理（RobotServiceImpl）
- `setRobotGoal` 仅在内存中保存目标点，并生成一条**简化直线路径**（每 0.1m 插值，非真实 A* 路径规划）。
- `updateRobotPose` 将位姿写入数据库，并通过 `DataServiceClient` 上报位置至数据服务（方法本身为异步执行）。
- `emergencyStop` 将机器人状态强制设为 `"故障"`，并上报数据服务。

### 6.4 LLM 对接（LLMServiceImpl）
- 通过 **WebSocket Client** 连接外部 LLM 服务，地址由 `llm.websocket.url` 配置（默认 `ws://localhost:8090/ws/llm`）。
- 超时时间：`llm.websocket.timeout-ms`（默认 5000ms）。
- 动作类型：`parse_natural_language`、`combine_tasks`、`get_behavior_tree_status`、`execute_behavior_node`。
- 解析自然语言后生成 `Task`，`commandType` 为动作大写，`robotCode` 为设备编码，`taskParams` 存储 JSON 结构化计划。

### 6.5 数据服务对接（DataServiceClient）
- 通过 **异步 HTTP 客户端**（`RestTemplate` + `@Async`）与外部数据服务（S-17）通信。
- 关键事件自动上报：任务创建、状态变更、任务分配、机器人状态/位置/心跳、系统日志。
- 上报带 **幂等键（request_id）** 与 **3 次指数退避重试**，最终失败不阻塞调度主流程。
- 配置项：`data-service.url`、`data-service.connect-timeout-ms`、`data-service.read-timeout-ms`、`data-service.retry.max-attempts`、`data-service.retry.backoff-multiplier`。

### 6.6 SLAM 管理（SLAMServiceImpl）
- **地图持久化**：`map` 表存储 pgm 二进制（LONGBLOB）与 yaml 文本（TEXT），支持多地图记录。
- **PGM 解析**：支持 ROS 标准 **P5 二进制灰度图**解析，自动处理 `negate`、占用/空闲阈值，像素坐标系翻转成地图坐标系。
- **YAML 解析**：读取 `resolution`、`origin`、`negate`、`occupied_thresh`、`free_thresh` 等元数据。
- **多地图切换**：`uploadMap` 上传新地图（默认不激活）；`switchMap` 切换激活地图并重新加载到内存；`listMaps` 查询地图列表；`deleteMap` 删除非激活地图。
- **启动加载**：`@PostConstruct` 优先加载数据库中 `is_active=1` 的地图；若无则自动导入 `slam/guli.pgm` + `slam/guli.yaml` 作为默认地图；再尝试从 `map_live` 恢复实时栅格与障碍物。
- **障碍物管理**：在原始地图（`baseGridData`）之上叠加动态障碍物，支持矩形、圆形、多边形；`resetMap` 可从数据库恢复原始地图。
- **实时地图持久化**：`map_live` 表保存当前内存中的实时栅格（含障碍物）与障碍物列表，服务重启后可自动恢复上次状态；障碍物发生变更时自动写入。
- **A* 路径规划**：8 方向搜索，基于当前内存栅格（含障碍物），带简单路径平滑。

### 6.7 ROS2 通信对接（RosBridgeServiceImpl）
- 通过 **HTTP REST API** 与 SLAM 端 `http_scheduler_bridge.py` 通信（默认 `http://172.16.25.219:9090`），替代原有的 WebSocket rosbridge 方案。
- **位姿获取**：每 1 秒 `GET /api/v1/scheduler/ros/pose` 轮询机器人位姿，解析 `position`（x, y）和 `orientation`（z, w）后计算 yaw，更新对应机器人数据库记录。
- **导航目标下发**：`POST /api/v1/scheduler/ros/goal` 发送导航目标，请求体支持 `{x, y, yaw}` 简写格式或完整 `PoseStamped` 格式；调用前会先更新内存目标与 Mock 路径。
- **健康检查**：`GET /healthz` 查询 SLAM 端存活状态。
- **注意**：Python HTTP 端未提供 `/map` 接口，地图数据由 `SLAMService` 通过数据库/文件独立管理，RosBridge 不再接收 `/map` 话题。

### 6.8 视觉-运动学算法通信对接
- **视觉识别接收端**（`VisionWebSocketHandler` + `WebSocketServerConfig`）：
  - 后端作为 WebSocket **服务端**，暴露端点 `/ws/vision` 供视觉识别客户端连接。
  - 接收视觉 YOLO 识别结果：`{obj_name, x, y, z}`。
  - 解析后自动调用 `MotionService.sendTargetObject()` 转发给运动学算法端。
  - 转发成功/失败均回传 ACK 给视觉客户端。
- **运动学算法客户端**（`MotionServiceImpl`）：
  - 后端作为 WebSocket **客户端**，连接外部运动学算法服务端（默认 `ws://localhost:8081/ws/motion`）。
  - 上行发送目标物体坐标：`{obj_name, x, y, z}`。
  - 下行接收双报文：正运动学末端位姿 + 逆运动学求解结果。
  - 当 `ik_solve=true` 时，自动创建 `commandType="GRAB"`、状态为 `QUEUED` 的任务并触发调度。
  - 当 `ik_solve=false` 时，记录系统日志，不创建任务。

### 6.9 动态优先级规划（TaskPriorityPlannerImpl）
- 每 30 秒（由 `@Scheduled` 驱动）自动重算所有 `QUEUED` 任务的 `dynamic_priority_score`。
- 评分公式（越低越优先）：
  1. **基础优先级**：`priority × 10`（priority 1~5）
  2. **等待时间**：每等待 1 分钟 +1，封顶 30
  3. **截止时间紧急度**：已过期 +100；≤1 小时按小时线性递减（最高 +50）；否则 0
  4. **任务类型权重**：可配置（如 `CHARGE=5`, `NAVIGATE=10`, `GRAB=15`, `LLM_PLAN=20`）
  5. **机器人匹配度**：到最近空闲机器人的欧氏距离 + 电量惩罚 `(100−battery)/10`
- 权重可通过 `scheduler.priority.weight.*` 配置调整。

---

## 7. 已知的关键不一致与注意事项

> 修改代码前务必关注以下已有注意事项。

1. **任务与机器人状态已完全统一**：
   - `StatusConstant.java` 定义：机器人状态为中文（`空闲`/`忙碌`/`故障`），任务状态为英文（`QUEUED`/`RUNNING`/`SUCCESS`/`FAILED`）。
   - 所有 Service、Controller 已统一使用 `StatusConstant` 常量，不再出现中文任务状态。

2. **测试代码仅为联调工具**：`src/test/java` 下存在与外部视觉、运动学、LLM 服务通信的测试客户端/服务端，**非单元测试/集成测试**。`pom.xml` 除 `spring-boot-starter-test` 外，新增 `java-websocket`（test scope）供 WebSocket 联调使用。

3. **CORS 全开放**：`CorsConfig` 允许所有来源、所有方法、携带凭证，生产环境需收紧。

4. **数据库凭据明文**：`application.yml` 中密码为明文，生产环境建议使用环境变量或配置中心。

5. **路径规划说明**：
   - `RobotServiceImpl.generatePath()` 仍仅做线性插值，非真实导航路径。
   - 如需真实路径请调用 `SLAMService.planPath`（A* 栅格路径规划）。

6. **H2 本地开发支持**：
   - `pom.xml` 已引入 `h2`（runtime scope）。
   - `src/main/resources/application-local.yml` 提供 H2 内存数据库配置，启动时自动执行 `schema-local.sql` 与 `data-local.sql`。
   - 本地开发可使用 `mvn spring-boot:run -Dspring-boot.run.profiles=local` 快速启动，无需 MySQL。

7. **数据服务上报为 Best-Effort**：
   - `DataServiceClient` 异步上报失败时仅记录日志，无本地持久化失败队列（当前为内存级丢弃）。
   - 如果数据服务长时间不可用，期间的上报数据会丢失。

---

## 8. 构建与开发命令

```bash
# 编译
mvn compile

# 运行
mvn spring-boot:run

# 打包为可执行 jar
mvn package

# 清理
mvn clean
```

---

## 9. 添加新功能时的建议

- **保持分层**：Controller 只负责参数解析和 `Result` 包装；业务逻辑下沉到 Service。
- **继续使用 `Result<T>`** 作为所有 REST 接口的返回类型。
- **状态值统一**：如果修改调度相关逻辑，优先统一使用 `StatusConstant` 中的常量（机器人状态为中文，任务状态为英文）。
- **乐观锁习惯**：对涉及并发修改的表（robot、task）继续使用 `UpdateWrapper` 带条件更新（如 `eq("status", oldStatus)`）。
- **日志记录**：任务完成、失败、机器人故障等关键事件应调用 `LogService.createLog(...)`。
- **实体 ID**：新建实体记录时继续使用 `UUID.randomUUID().toString().replace("-", "")`。
- **数据服务上报**：涉及任务/机器人/日志的关键状态变更，如需同步到数据服务（S-17），在对应 Service 方法中通过 `DataServiceClient` 异步上报，不阻塞主流程。
- **如需新增表**：
  1. 在 `db_init.sql` 中补充建表语句（字段长度建议 `VARCHAR(36)`）；
  2. 在 `entity/` 下新建实体类（`@Data`、`@TableName`、`@TableId`）；
  3. 在 `mapper/` 下新建接口继承 `BaseMapper<T>`；
  4. 按需添加 Service 与 Controller。
- **如需新增前端 DTO**：在 `dto/` 包下新建，保持与 Entity 分离。
