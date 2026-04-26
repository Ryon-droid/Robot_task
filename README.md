# Robot Scheduler 机器人调度系统

## 一、项目概述

Robot Scheduler 是一个基于 Spring Boot 的机器人任务调度系统，实现多机器人之间的任务智能分配、状态跟踪、SLAM 地图管理与 ROS2 通信对接。

### 核心功能
- **任务管理**：创建、查询、状态更新，支持动态优先级调度
- **机器人管理**：列表查询、实时位姿获取、目标点设置、紧急停止
- **智能调度**：优先级队列 + 乐观锁分配，支持多维度动态优先级重算
- **数据服务对接**：与外部数据服务（S-17）双向 HTTP 通信，关键事件异步上报，暴露 `/scheduler/...` 外部查询/控制接口
- **LLM 对接**：自然语言指令解析，自动拆分为子任务列表
- **SLAM 地图**：OccupancyGrid 栅格地图、障碍物/空气墙管理、A* 路径规划
- **ROS2 通信**：通过 rosbridge_suite 实时接收地图/位姿，下发导航目标
- **日志记录**：任务完成/失败/状态流转自动记录，异步推送至数据服务

---

## 二、技术架构

### 技术栈
| 组件 | 技术 |
|------|------|
| 框架 | Spring Boot 2.7.18 |
| ORM | MyBatis-Plus 3.5.3.1 |
| 数据库 | MySQL 8.0 |
| 构建工具 | Maven |
| JDK版本 | Java 17 |
| 工具类 | Lombok |
| WebSocket | Spring Boot Starter WebSocket |

### 项目结构
```
robot-scheduler/
├── src/main/java/com/robot/scheduler/
│   ├── SchedulerApplication.java          # 启动类
│   ├── common/                            # 公共类
│   │   ├── Result.java                    # 统一响应结果
│   │   ├── StatusConstant.java            # 状态常量定义
│   │   ├── BusinessException.java         # 业务异常
│   │   └── GlobalExceptionHandler.java    # 全局异常处理
│   ├── config/
│   │   ├── CorsConfig.java                # 跨域配置
│   │   └── AsyncConfig.java               # 异步线程池配置（数据上报）
│   ├── controller/                        # 控制器层
│   │   ├── NewTaskController.java         # 任务管理 API（前端）
│   │   ├── RobotController.java           # 机器人管理 API（前端）
│   │   ├── LogController.java             # 日志查询 API（前端）
│   │   ├── SchedulerExternalController.java # 外部数据服务 API（/scheduler/...）
│   │   ├── SLAMController.java            # SLAM 地图与路径规划 API
│   │   ├── LLMController.java             # LLM 自然语言解析 API
│   │   └── RosBridgeController.java       # ROS2 通信状态与导航目标 API
│   ├── entity/                            # 实体类
│   │   ├── Task.java                      # 任务实体
│   │   ├── Robot.java                     # 机器人实体
│   │   ├── Log.java                       # 日志实体
│   │   └── TaskRecord.java                # 任务状态流转记录
│   ├── mapper/                            # MyBatis Mapper
│   │   ├── TaskMapper.java
│   │   ├── RobotMapper.java
│   │   ├── LogMapper.java
│   │   └── TaskRecordMapper.java
│   ├── service/                           # 服务接口
│   │   ├── TaskService.java
│   │   ├── RobotService.java
│   │   ├── LogService.java
│   │   ├── ScheduleService.java           # 核心调度接口
│   │   ├── TaskPriorityPlanner.java       # 动态优先级计算
│   │   ├── DataServiceClient.java         # 数据服务 HTTP 上报客户端
│   │   ├── LLMService.java                # LLM WebSocket 交互
│   │   ├── SLAMService.java               # SLAM 地图与路径规划
│   │   ├── RosBridgeService.java          # ROS2 WebSocket 通信
│   │   └── StateTrackService.java         # 状态变更追踪
│   └── service/impl/                      # 服务实现
│       ├── TaskServiceImpl.java
│       ├── RobotServiceImpl.java
│       ├── LogServiceImpl.java
│       ├── ScheduleServiceImpl.java       # 优先级队列 + 乐观锁调度
│       ├── TaskPriorityPlannerImpl.java   # 5 维动态优先级评分
│       ├── LLMServiceImpl.java
│       ├── SLAMServiceImpl.java           # OccupancyGrid + A*
│       ├── RosBridgeServiceImpl.java      # rosbridge WebSocket 客户端
│       └── StateTrackServiceImpl.java
├── src/main/resources/
│   ├── application.yml                    # 配置文件
│   └── mapper/                            # Mapper XML 目录
├── db_init.sql                            # 数据库初始化脚本
└── pom.xml                                # Maven 配置
```

---

## 三、数据库设计

### 3.1 任务表 (task)
| 字段 | 类型 | 说明 |
|------|------|------|
| task_id | VARCHAR(32) | 主键，UUID |
| task_name | VARCHAR(64) | 任务名称 |
| command_type | VARCHAR(32) | 命令类型 |
| priority | INT | 优先级 (1-5，1最高) |
| robot_id | VARCHAR(32) | 执行机器人ID |
| robot_code | VARCHAR(32) | 机器人编码 |
| status | VARCHAR(16) | 状态：QUEUED → RUNNING → SUCCESS / FAILED |
| task_params | JSON | 任务参数 |
| create_time | DATETIME | 创建时间 |
| start_time | DATETIME | 开始时间 |
| finish_time | DATETIME | 完成/失败时间 |
| fail_reason | VARCHAR(255) | 失败原因 |
| deadline | DATETIME | 任务截止时间 |
| estimated_duration | INT | 预估执行时长（秒） |
| dynamic_priority_score | DOUBLE | 动态优先级分数（越低越优先） |

### 3.2 机器人表 (robot)
| 字段 | 类型 | 说明 |
|------|------|------|
| robot_id | VARCHAR(32) | 主键，UUID |
| robot_name | VARCHAR(64) | 机器人名称 |
| robot_code | VARCHAR(32) | 机器人编码（与 ROS/LLM 对接） |
| status | VARCHAR(16) | 状态：空闲/忙碌/故障 |
| load | INT | 当前负载（任务数） |
| battery | INT | 电量百分比 |
| last_heartbeat | DATETIME | 最后心跳时间 |
| x | DOUBLE | X坐标（米，SLAM地图坐标系） |
| y | DOUBLE | Y坐标（米） |
| yaw | DOUBLE | 朝向角度（弧度） |

### 3.3 任务状态记录表 (task_record)
| 字段 | 类型 | 说明 |
|------|------|------|
| record_id | VARCHAR(32) | 主键，UUID |
| task_id | VARCHAR(32) | 关联任务ID |
| old_status | VARCHAR(16) | 变更前状态 |
| new_status | VARCHAR(16) | 变更后状态 |
| change_time | DATETIME | 变更时间 |
| change_reason | VARCHAR(255) | 变更原因 |

### 3.4 日志表 (log)
| 字段 | 类型 | 说明 |
|------|------|------|
| log_id | BIGINT | 主键，自增 |
| log_type | VARCHAR(32) | 类型：TASK / ROBOT / SYSTEM |
| message | TEXT | 日志内容 |
| reference_id | VARCHAR(32) | 关联ID |
| create_time | DATETIME | 创建时间 |

---

## 四、核心功能说明

### 4.1 任务服务 (TaskServiceImpl)
- 任务创建（自动生成 UUID、计算动态优先级分数）
- 任务状态更新（RUNNING/SUCCESS/FAILED 自动记录时间戳与日志）
- 任务查询与筛选（支持按状态、机器人ID筛选）

### 4.2 机器人服务 (RobotServiceImpl)
- 机器人列表查询
- 目标点设置（内存存储 + 简化直线路径生成）
- 位姿更新（写入数据库）

### 4.3 调度服务 (ScheduleServiceImpl)
- **优先级队列**：`PriorityBlockingQueue`，启动时加载前 1000 条 `QUEUED` 任务
- **动态优先级**：每 30 秒自动重算所有待执行任务分数（基于基础优先级、等待时间、截止时间、任务类型权重、机器人匹配度）
- **乐观锁分配**：`UpdateWrapper` 条件更新防止并发冲突
- **故障回退**：机器人故障时，将其 `RUNNING` 任务回退为 `QUEUED` 并重新入队

### 4.4 LLM 服务 (LLMServiceImpl)
- WebSocket 连接外部 LLM 服务
- 自然语言解析为结构化子任务列表
- 每个子任务独立生成 `Task` 记录，`commandType` 为动作大写，`robotCode` 为设备编码

### 4.5 SLAM 服务 (SLAMServiceImpl)
- **OccupancyGrid 地图**：分辨率、宽高、原点、栅格数据管理
- **障碍物管理**：支持矩形、圆形、多边形；区分实体障碍物与空气墙
- **A* 路径规划**：8 方向搜索 + 欧式距离启发函数 + 路径简化

### 4.6 ROS2 通信服务 (RosBridgeServiceImpl)
- WebSocket 客户端连接 `rosbridge_server`
- 自动订阅 `/map` → 解析后更新内存栅格地图
- 自动订阅 `/amcl_pose` → 解析位姿后更新对应机器人数据库记录
- 支持通过 `/goal_pose` 向 ROS2 发送真实导航目标

### 4.7 日志服务 (LogServiceImpl)
- 任务完成/失败自动写入 `log` 表
- 支持按类型、关联ID查询

---

## 五、API 接口文档

### 5.1 任务管理

#### 创建任务
```
POST /api/v1/tasks
Content-Type: application/json

Request:
{
    "robotId": "6fb3e5b6-...",
    "commandType": "MOVE_TO",
    "priority": 1,
    "estimatedDuration": 120,
    "deadline": "2026-04-27T10:00:00",
    "params": { "x": 20, "y": 8, "speed": 1.5 }
}

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "taskId": "xxx",
        "robotId": "6fb3e5b6-...",
        "robotCode": "RBT-001",
        "commandType": "MOVE_TO",
        "priority": 1,
        "status": "QUEUED",
        "createdAt": 1760000000000,
        "params": { "x": 20, "y": 8 }
    }
}
```

#### 获取任务列表
```
GET /api/v1/tasks?status=RUNNING&robotId=xxx
```

#### 获取任务详情
```
GET /api/v1/tasks/{taskId}
```

#### 更新任务状态
```
PATCH /api/v1/tasks/{taskId}/status
Content-Type: application/json

Request:
{
    "status": "SUCCESS",
    "reason": ""
}
```

### 5.2 机器人管理

#### 获取机器人列表
```
GET /api/robots

Response:
{
    "code": 200,
    "data": [
        {
            "id": "robot001",
            "name": "机器人1号",
            "x": 1.5,
            "y": 2.0,
            "status": "空闲"
        }
    ]
}
```

#### 获取实时位姿
```
GET /api/robots/pose

Response:
{
    "code": 200,
    "data": [
        {
            "id": "robot001",
            "x": 1.5,
            "y": 2.0,
            "yaw": 0.785
        }
    ]
}
```

#### 设置目标点
```
POST /api/robot/goal
Content-Type: application/json

Request:
{
    "robotId": "robot001",
    "x": 5.0,
    "y": 3.0,
    "yaw": 1.57
}
```

#### 获取规划路径
```
GET /api/robot/path?robotId=robot001
```

### 5.3 日志查询
```
GET /api/v1/logs?type=TASK&referenceId=task001
```

### 5.4 SLAM 地图

#### 获取地图
```
GET /api/v1/scheduler/slam/map
```

#### 更新地图
```
POST /api/v1/scheduler/slam/map
```

#### 重置地图
```
POST /api/v1/scheduler/slam/map/reset
```

#### 添加障碍物
```
POST /api/v1/scheduler/slam/obstacles
Content-Type: application/json

Request:
{
    "type": "obstacle",
    "shape": "rectangle",
    "x": 5.0,
    "y": 3.0,
    "width": 2.0,
    "height": 1.0
}
```

#### A* 路径规划
```
POST /api/v1/scheduler/slam/path/plan
Content-Type: application/json

Request:
{
    "startX": 0.0,
    "startY": 0.0,
    "goalX": 10.0,
    "goalY": 8.0
}
```

### 5.5 LLM 解析
```
POST /api/v1/scheduler/llm/parse
Content-Type: application/json

Request:
{
    "instruction": "让机器人A去会议室拿杯子"
}

Response:
{
    "code": 200,
    "data": [
        {
            "taskId": "xxx",
            "taskName": "杯子-机器人A-GRAB",
            "commandType": "GRAB",
            "robotCode": "机器人A",
            "priority": 3,
            "status": "QUEUED"
        }
    ]
}
```

### 5.6 ROS2 通信

#### 查询连接状态
```
GET /api/v1/scheduler/ros/status

Response:
{
    "code": 200,
    "data": {
        "connected": true,
        "url": "ws://localhost:9090",
        "mapMessageCount": 120,
        "poseMessageCount": 500
    }
}
```

#### 发送导航目标
```
POST /api/v1/scheduler/ros/goal
Content-Type: application/json

Request:
{
    "robotCode": "tb3_0",
    "x": 5.0,
    "y": 3.0,
    "yaw": 0.0
}
```

### 5.7 外部数据服务接口（/scheduler/...）

> 面向数据服务（S-17）的专用接口，供外部系统查询调度器实时状态与控制任务。

#### 获取机器人列表
```
GET /scheduler/robots

Response:
{
    "code": 200,
    "data": [
        {
            "robotId": "robot001",
            "robotName": "机器人1号",
            "robotCode": "RBT-001",
            "status": "空闲",
            "load": 0,
            "battery": 85,
            "x": 1.5,
            "y": 2.0,
            "yaw": 0.785,
            "lastHeartbeat": 1760000000000
        }
    ]
}
```

#### 获取单个机器人详情
```
GET /scheduler/robots/{robotId}
```

#### 获取任务列表
```
GET /scheduler/tasks?status=QUEUED&robotId=xxx
```

#### 获取任务详情
```
GET /scheduler/tasks/{taskId}
```

#### 获取调度队列（按优先级排序）
```
GET /scheduler/tasks/queue
```

#### 取消任务
```
POST /scheduler/tasks/{taskId}/cancel

Response:
{
    "code": 200,
    "data": {
        "taskId": "xxx",
        "action": "cancel"
    }
}
```

#### 重新分配任务
```
POST /scheduler/tasks/{taskId}/reassign
```

#### 调整任务优先级
```
POST /scheduler/tasks/{taskId}/priority
Content-Type: application/json

Request:
{
    "priority": 1
}
```

#### 机器人紧急停止
```
POST /scheduler/robots/{robotId}/emergency_stop
```

---

## 六、状态常量定义

```java
// 机器人状态
ROBOT_STATUS_IDLE   = "空闲"
ROBOT_STATUS_BUSY   = "忙碌"
ROBOT_STATUS_ERROR  = "故障"

// 任务状态
TASK_STATUS_PENDING    = "QUEUED"    // 待执行
TASK_STATUS_RUNNING    = "RUNNING"   // 执行中
TASK_STATUS_COMPLETED  = "SUCCESS"   // 已完成
TASK_STATUS_FAILED     = "FAILED"    // 失败

// 任务优先级
PRIORITY_HIGHEST = 1
PRIORITY_HIGH    = 2
PRIORITY_NORMAL  = 3
PRIORITY_LOW     = 4
PRIORITY_LOWEST  = 5
```

---

## 七、配置说明

### application.yml
```yaml
server:
  port: 8080

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

scheduler:
  priority:
    weight:
      base: 1.0
      waiting: 1.0
      deadline: 1.0
      type: 1.0
      robot: 1.0
    recalculation-interval-ms: 30000

llm:
  websocket:
    url: ws://localhost:8090/ws/llm
    timeout-ms: 5000

rosbridge:
  websocket:
    url: ws://localhost:9090
  topics:
    map: /map
    pose: /amcl_pose
    goal: /goal_pose
  default-robot-code: ""

# 数据服务（S-17）对接配置
data-service:
  url: http://localhost:8000          # 数据服务地址
  connect-timeout-ms: 3000
  read-timeout-ms: 5000
  retry:
    max-attempts: 3
    backoff-multiplier: 1000
```

---

## 八、数据服务对接架构

Scheduler 与数据服务（S-17）采用 **HTTP API 双向调用** 方案：

### 通信方向

| 方向 | 触发方 | 接口 | 说明 |
|------|--------|------|------|
| Scheduler → 数据服务 | 关键事件后异步上报 | `POST /api/v1/tasks` 等 | 任务创建、状态变更、日志、机器人状态/位置 |
| 数据服务 → Scheduler | 外部查询/控制 | `GET /scheduler/...` | 查询队列、机器人、任务；取消/重分配任务 |

### 上报事件清单

| 事件 | 上报接口 | 位置 |
|------|---------|------|
| 创建任务 | `POST /api/v1/tasks` | `TaskServiceImpl.createTask()` |
| 任务状态变更 | `POST /api/v1/tasks/{id}/status` | `TaskServiceImpl.updateTaskStatus()` |
| 任务分配成功 | `PUT /api/v1/tasks/{id}` + 状态变更 | `ScheduleServiceImpl.tryAssignTask()` |
| 机器人状态更新 | `POST /api/v1/robots/status` | `StateTrackServiceImpl.updateRobotState()` |
| 机器人位置更新 | `POST /api/v1/navigation/positions` | `RobotServiceImpl.updateRobotPose()` |
| 系统日志 | `POST /api/v1/logs/operation` 或 `/error` | `LogServiceImpl.createLog()` |

> 所有上报均为**异步执行**（`@Async`），带 **3 次指数退避重试**，最终失败不阻塞调度主流程。

---

## 九、数据库初始化

执行 `db_init.sql` 创建数据库和表（由数据库管理员维护）：

```sql
-- Robot Scheduler 数据库初始化脚本（MySQL 8.0+）

CREATE DATABASE IF NOT EXISTS robot_scheduler
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE robot_scheduler;

-- 机器人表
CREATE TABLE robot (
    robot_id        VARCHAR(32)     NOT NULL COMMENT '机器人ID（程序生成UUID）',
    robot_name      VARCHAR(64)     NULL COMMENT '机器人名称',
    robot_code      VARCHAR(64)     NULL COMMENT '机器人编码',
    status          VARCHAR(16)     NOT NULL DEFAULT '空闲' COMMENT '状态：空闲 / 忙碌 / 故障',
    load            INT             NOT NULL DEFAULT 0 COMMENT '当前负载',
    last_heartbeat  DATETIME        NULL COMMENT '最后心跳时间',
    battery         INT             NULL COMMENT '电池电量（百分比）',
    x               DOUBLE          NULL COMMENT '位置X坐标（米）',
    y               DOUBLE          NULL COMMENT '位置Y坐标（米）',
    yaw             DOUBLE          NULL COMMENT '朝向角度（弧度）',
    PRIMARY KEY (robot_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='机器人信息表';

-- 任务表
CREATE TABLE task (
    task_id                 VARCHAR(32)     NOT NULL COMMENT '任务ID（程序生成UUID）',
    task_name               VARCHAR(128)    NULL COMMENT '任务名称',
    command_type            VARCHAR(64)     NULL COMMENT '指令类型',
    priority                INT             NOT NULL DEFAULT 3 COMMENT '优先级 1-5，1最高',
    robot_id                VARCHAR(32)     NULL COMMENT '分配到的机器人ID',
    robot_code              VARCHAR(64)     NULL COMMENT '机器人编码',
    status                  VARCHAR(16)     NOT NULL DEFAULT 'QUEUED' COMMENT '状态：QUEUED / RUNNING / SUCCESS / FAILED',
    task_params             JSON            NULL COMMENT '任务参数（JSON格式）',
    create_time             DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    start_time              DATETIME        NULL COMMENT '开始执行时间',
    finish_time             DATETIME        NULL COMMENT '完成时间',
    fail_reason             VARCHAR(512)    NULL COMMENT '失败原因',
    deadline                DATETIME        NULL COMMENT '任务截止时间（动态优先级规划）',
    estimated_duration      INT             NULL COMMENT '预估执行时长（秒）',
    dynamic_priority_score  DOUBLE          NULL COMMENT '动态优先级分数（越低越优先）',
    PRIMARY KEY (task_id),
    INDEX idx_status (status),
    INDEX idx_robot_id (robot_id),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='任务信息表';

-- 任务状态记录表
CREATE TABLE task_record (
    record_id       VARCHAR(32)     NOT NULL COMMENT '记录ID（程序生成UUID）',
    task_id         VARCHAR(32)     NOT NULL COMMENT '关联任务ID',
    old_status      VARCHAR(16)     NULL COMMENT '变更前状态',
    new_status      VARCHAR(16)     NULL COMMENT '变更后状态',
    change_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '变更时间',
    change_reason   VARCHAR(512)    NULL COMMENT '变更原因',
    PRIMARY KEY (record_id),
    INDEX idx_task_id (task_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='任务状态变更记录表';

-- 日志表
CREATE TABLE log (
    log_id          BIGINT          NOT NULL AUTO_INCREMENT COMMENT '日志ID（自增）',
    log_type        VARCHAR(32)     NULL COMMENT '日志类型：TASK / ROBOT / SYSTEM',
    message         TEXT            NULL COMMENT '日志内容',
    reference_id    VARCHAR(32)     NULL COMMENT '关联ID（任务ID或机器人ID）',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (log_id),
    INDEX idx_log_type (log_type),
    INDEX idx_reference_id (reference_id),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='系统日志表';
```

---

## 十、启动方式

```bash
# 1. 创建数据库并执行 db_init.sql

# 2. 修改 application.yml 中的数据库配置

# 3. 启动应用
mvn spring-boot:run

# 4. 访问 API
http://localhost:8080
```

### ROS2 通信启动（Ubuntu 侧）
```bash
# 安装 rosbridge_suite
sudo apt install ros-<distro>-rosbridge-suite

# 启动 WebSocket 桥接
ros2 launch rosbridge_server rosbridge_websocket_launch.xml
```

---

> **文档版本：** v3.1.0  
> **更新日期：** 2026-04-26
