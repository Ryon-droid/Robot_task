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
- SLAM 地图与障碍物的内存级管理（当前为 Mock 实现）

项目不包含任何单元测试或集成测试代码。

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
| 工具类 | Lombok |

### 启动入口
- `com.robot.scheduler.SchedulerApplication`
- 注解：`@SpringBootApplication`、`@MapperScan("com.robot.scheduler.mapper")`、`@EnableScheduling`

### 运行方式
```bash
# 1. 在 MySQL 中执行 db_init.sql 创建数据库与表
# 2. 按需修改 src/main/resources/application.yml 中的数据库连接信息
# 3. 启动
mvn spring-boot:run
```
- 默认端口：`8080`
- 数据库名：`robot_scheduler`

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
│   │   ├── StatusConstant.java            # 状态常量（机器人/任务/优先级）
│   │   ├── BusinessException.java         # 业务异常（携带 code）
│   │   └── GlobalExceptionHandler.java    # 全局异常拦截
│   ├── config/
│   │   ├── CorsConfig.java                # 跨域配置（全开放）
│   │   └── AsyncConfig.java               # 异步线程池配置（数据上报）
│   ├── controller/                        # REST API 层
│   │   ├── NewTaskController.java         # 任务管理 API  (/api/v1/tasks)
│   │   ├── RobotController.java           # 机器人前端 API (/api/robots ...)
│   │   ├── LogController.java             # 日志查询 API   (/api/v1/logs)
│   │   ├── SchedulerExternalController.java # 外部数据服务 API (/scheduler/*)
│   │   ├── LLMController.java             # LLM 交互      (/api/v1/scheduler/llm/*)
│   │   └── SLAMController.java            # SLAM 地图管理  (/api/v1/scheduler/slam/*)
│   ├── entity/                            # 实体类（与数据库表对应）
│   │   ├── Task.java                      # 任务实体（实现 Comparable，按优先级+创建时间排序）
│   │   ├── Robot.java                     # 机器人实体
│   │   ├── Log.java                       # 日志实体
│   │   └── TaskRecord.java                # 任务状态变更记录
│   ├── mapper/                            # MyBatis-Plus Mapper 接口
│   │   ├── TaskMapper.java
│   │   ├── RobotMapper.java
│   │   ├── LogMapper.java
│   │   └── TaskRecordMapper.java
│   ├── service/                           # 服务接口
│   │   ├── TaskService.java
│   │   ├── RobotService.java
│   │   ├── LogService.java
│   │   ├── ScheduleService.java
│   │   ├── DataServiceClient.java         # 数据服务 HTTP 上报客户端
│   │   ├── LLMService.java
│   │   ├── SLAMService.java
│   │   └── StateTrackService.java
│   └── service/impl/                      # 服务实现
│       ├── TaskServiceImpl.java
│       ├── RobotServiceImpl.java
│       ├── LogServiceImpl.java
│       ├── ScheduleServiceImpl.java       # 核心调度逻辑（优先级队列 + 乐观锁）
│       ├── LLMServiceImpl.java            # 通过 WebSocket 调用外部 LLM
│       ├── SLAMServiceImpl.java           # 内存级地图 Mock
│       └── StateTrackServiceImpl.java     # 状态变更记录与机器人状态更新
└── src/main/resources/
    ├── application.yml                    # Spring Boot 配置
    └── mapper/                            # 预留 XML Mapper 目录（当前无文件）
```

---

## 4. 数据库设计

执行 `db_init.sql` 初始化：

- **`robot`** — 机器人信息（robot_id, robot_name, status, load, battery, x, y, yaw, last_heartbeat）
- **`robot`** — 机器人信息（robot_id, robot_name, robot_code, status, load, battery, x, y, yaw, last_heartbeat）
- **`task`** — 任务信息（task_id, task_name, command_type, priority, robot_id, robot_code, status, task_params(JSON), 时间戳, fail_reason, deadline, estimated_duration, dynamic_priority_score）
- **`task_record`** — 任务状态流转记录（record_id, task_id, old_status, new_status, change_time, change_reason）
- **`log`** — 系统/任务/机器人日志（log_id, log_type, message, reference_id, create_time）

### 数据源配置（application.yml）
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
- 主键生成：代码中统一使用 `UUID.randomUUID().toString().replace("-", "")`。

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
- 内部维护一个 `PriorityBlockingQueue<Task>`，按 **priority 升序**（1 最高），再按 **createTime 升序**。
- 启动时从数据库加载 `status='待执行'` 的任务到队列（上限 1000）。
- `triggerSchedule()` 被调用时（如创建任务后、机器人回调完成后、手动触发），尝试为队列中的任务分配空闲机器人。
- **乐观锁分配流程**：
  1. 查询 `status='空闲'` 且 `load` 最小的机器人；
  2. 用 `UpdateWrapper` 条件更新任务 `status='待执行' → '执行中'`；
  3. 用 `UpdateWrapper` 条件更新机器人 `status='空闲' → '忙碌'` 并 `load+1`；
  4. 任意一步失败则回滚或跳过。
- 机器人故障时，将其正在执行的任务状态回退为 `"待执行"` 并重新入队，机器人状态设为 `"故障"`。

### 6.2 任务状态流转
- Controller 与 `TaskServiceImpl` 中使用的状态串：**`QUEUED` → `RUNNING` → `SUCCESS` / `FAILED`**
- `ScheduleServiceImpl` 与 `StateTrackServiceImpl` 中使用的中文状态串：**`待执行` → `执行中` → `已完成` / `执行失败`**
- 更新为 `SUCCESS` 或 `FAILED` 时，自动写 `log` 表记录。

### 6.3 机器人管理（RobotServiceImpl）
- `setRobotGoal` 仅在内存中保存目标点，并生成一条**简化直线路径**（每 0.1m 插值，非真实 A* 路径规划）。
- `updateRobotPose` 将位姿写入数据库，并异步上报位置至数据服务。
- `emergencyStop` 将机器人状态强制设为 `"故障"`，并上报数据服务。

### 6.4 LLM 对接（LLMServiceImpl）
- 通过 **WebSocket Client** 连接外部 LLM 服务，地址由 `llm.websocket.url` 配置（默认 `ws://localhost:8090/ws/llm`）。
- 超时时间：`llm.websocket.timeout-ms`（默认 5000ms）。
- 动作类型：`parse_natural_language`、`combine_tasks`、`get_behavior_tree_status`、`execute_behavior_node`。
- 解析自然语言后生成 `Task`，`commandType` 为 `LLM_PLAN`，`taskParams` 存储 JSON 结构化计划。

### 6.4 数据服务对接（DataServiceClient）
- 通过 **异步 HTTP 客户端**（`RestTemplate` + `@Async`）与外部数据服务（S-17）通信。
- 关键事件自动上报：任务创建、状态变更、任务分配、机器人状态/位置、系统日志。
- 上报带 **幂等键（request_id）** 与 **3 次指数退避重试**，最终失败不阻塞调度主流程。
- 配置项：`data-service.url`、`data-service.retry.max-attempts`。

### 6.5 SLAM 管理（SLAMServiceImpl）
- 当前为**内存 Mock**，重启后数据丢失。
- 支持地图数据读写、障碍物增删改、地图重置。

---

## 7. 已知的关键不一致与注意事项

> 修改代码前务必关注以下已有不一致，避免引入更严重的状态匹配问题。

1. **任务状态值混用（已部分修复）**：
   - `StatusConstant.java` 当前定义：机器人状态为中文（`空闲`/`忙碌`/`故障`），任务状态为英文（`QUEUED`/`RUNNING`/`SUCCESS`/`FAILED`）。
   - `ScheduleServiceImpl`、`StateTrackServiceImpl`、`TaskServiceImpl` 已统一使用 `StatusConstant` 常量，核心调度层不再混用。
   - 但 `AGENTS.md` 历史文档中提到的中文任务状态（`待执行`/`执行中`/`已完成`/`执行失败`）在代码中**已不存在**，若外部系统按旧文档对接需注意。

2. **无测试代码**：项目中没有任何 `src/test` 目录或测试类。

3. **CORS 全开放**：`CorsConfig` 允许所有来源、所有方法、携带凭证，生产环境需收紧。

4. **数据库凭据明文**：`application.yml` 中密码为明文 `password`。

5. **SLAM 与路径规划为 Mock**：
   - `SLAMServiceImpl` 数据仅存于内存 HashMap。
   - `RobotServiceImpl.generatePath()` 仅做线性插值，非真实导航路径。

6. **数据服务上报为 Best-Effort**：
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
  1. 在 `db_init.sql` 中补充建表语句（由数据库管理员维护）；
  2. 在 `entity/` 下新建实体类（`@Data`、`@TableName`、`@TableId`）；
  3. 在 `mapper/` 下新建接口继承 `BaseMapper<T>`；
  4. 按需添加 Service 与 Controller。
