# Robot Scheduler 机器人调度系统

## 一、项目概述

Robot Scheduler 是一个基于 Spring Boot 的机器人任务调度系统，实现多机器人之间的任务智能分配、状态跟踪、SLAM 地图管理与 ROS2 通信对接。

### 核心功能
- **任务管理**：创建、查询、状态更新，支持动态优先级调度；支持取消、重分配、手动调整优先级
- **机器人管理**：列表查询、实时位姿获取、目标点设置、紧急停止
- **智能调度**：优先级队列 + 乐观锁分配，支持多维度动态优先级重算
- **数据服务对接**：与外部数据服务（S-17）双向 HTTP 通信，关键事件异步上报，暴露 `/scheduler/...` 外部查询/控制接口
- **LLM 对接**：自然语言指令解析，自动拆分为子任务列表
- **SLAM 地图**：支持 ROS 标准 `.pgm` + `.yaml` 地图格式解析，MySQL 持久化存储，多地图切换；障碍物/空气墙管理、A* 路径规划；**实时地图快照自动落库（`map_live`），重启可恢复**
- **ROS2 通信**：通过 rosbridge_suite 实时接收地图/位姿，下发导航目标
- **视觉-运动学算法对接**：接收视觉识别结果并转发给运动学算法端，逆运动学求解成功后自动创建抓取任务并触发调度
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
│   │   ├── RosBridgeController.java       # ROS2 通信状态与导航目标 API
│   │   └── MotionController.java          # 视觉-运动学算法通信 API
│   ├── dto/
│   │   ├── TaskRankingDTO.java            # 任务排行 DTO
│   │   ├── ForwardKinematicsResult.java   # 正运动学末端位姿结果
│   │   ├── InverseKinematicsResult.java   # 逆运动学求解结果
│   │   └── MotionTargetRequest.java       # 目标物体请求 DTO
│   ├── entity/                            # 实体类
│   │   ├── Task.java                      # 任务实体
│   │   ├── Robot.java                     # 机器人实体
│   │   ├── Log.java                       # 日志实体
│   │   ├── TaskRecord.java                # 任务状态流转记录
│   │   ├── MapInfo.java                   # SLAM 静态地图实体
│   │   └── MapLive.java                   # SLAM 实时地图快照实体
│   ├── mapper/                            # MyBatis Mapper
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
│   │   ├── TaskPriorityPlanner.java       # 动态优先级计算
│   │   ├── DataServiceClient.java         # 数据服务 HTTP 上报客户端
│   │   ├── LLMService.java                # LLM WebSocket 交互
│   │   ├── MotionService.java             # 视觉-运动学算法 WebSocket 通信
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
│       ├── StateTrackServiceImpl.java
│       ├── MotionServiceImpl.java         # 运动学算法 WebSocket 客户端
│       └── VisionWebSocketHandler.java    # 视觉识别 WebSocket 服务端处理器
├── src/main/resources/
│   ├── application.yml                    # 配置文件
│   └── mapper/                            # Mapper XML 目录
├── db_init.sql                            # 数据库初始化脚本
├── pom.xml                                # Maven 配置
├── realsense_yolo_csv.py                  # 外部视觉识别脚本（RealSense + YOLOv8）
└── src/test/java/                         # 联调测试工具（非单元测试）
    ├── MotionCommTestClient.java          # 运动学算法端 WebSocket 测试客户端
    └── com/robot/scheduler/...            # 视觉/LLM 测试服务端与客户端
```

---

## 三、数据库设计

### 3.1 任务表 (task)
| 字段 | 类型 | 说明 |
|------|------|------|
| task_id | VARCHAR(36) | 主键，UUID（代码生成 32 位无横线） |
| task_name | VARCHAR(128) | 任务名称 |
| command_type | VARCHAR(64) | 命令类型 |
| priority | INT | 优先级 (1-5，1最高) |
| robot_id | VARCHAR(36) | 执行机器人ID |
| robot_code | VARCHAR(64) | 机器人编码 |
| status | VARCHAR(16) | 状态：QUEUED → RUNNING → SUCCESS / FAILED |
| task_params | JSON | 任务参数 |
| create_time | DATETIME | 创建时间 |
| start_time | DATETIME | 开始执行时间 |
| finish_time | DATETIME | 完成/失败时间 |
| fail_reason | VARCHAR(512) | 失败原因 |
| deadline | DATETIME | 任务截止时间 |
| estimated_duration | INT | 预估执行时长（秒） |
| dynamic_priority_score | DOUBLE | 动态优先级分数（越低越优先） |

### 3.2 机器人表 (robot)
| 字段 | 类型 | 说明 |
|------|------|------|
| robot_id | VARCHAR(36) | 主键，UUID |
| robot_name | VARCHAR(64) | 机器人名称 |
| robot_code | VARCHAR(64) | 机器人编码（与 ROS/LLM 对接） |
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
| record_id | VARCHAR(36) | 主键，UUID |
| task_id | VARCHAR(36) | 关联任务ID |
| old_status | VARCHAR(16) | 变更前状态 |
| new_status | VARCHAR(16) | 变更后状态 |
| change_time | DATETIME | 变更时间 |
| change_reason | VARCHAR(512) | 变更原因 |

### 3.4 日志表 (log)
| 字段 | 类型 | 说明 |
|------|------|------|
| log_id | BIGINT | 主键，自增 |
| log_type | VARCHAR(32) | 类型：TASK / ROBOT / SYSTEM |
| message | TEXT | 日志内容 |
| reference_id | VARCHAR(36) | 关联ID |
| create_time | DATETIME | 创建时间 |

### 3.5 地图表 (map)
| 字段 | 类型 | 说明 |
|------|------|------|
| map_id | VARCHAR(36) | 主键，UUID |
| map_name | VARCHAR(100) | 地图名称 |
| pgm_data | LONGBLOB | PGM 栅格图二进制 |
| yaml_data | TEXT | YAML 元数据文本 |
| resolution | DOUBLE | 分辨率（米/像素） |
| origin_x | DOUBLE | 地图原点 X（米） |
| origin_y | DOUBLE | 地图原点 Y（米） |
| origin_yaw | DOUBLE | 地图原点偏航角（弧度） |
| width | INT | 地图宽度（像素） |
| height | INT | 地图高度（像素） |
| negate | INT | 是否反转像素值 0/1 |
| occupied_thresh | DOUBLE | 占用阈值 |
| free_thresh | DOUBLE | 空闲阈值 |
| is_active | TINYINT | 是否当前激活 0/1 |
| create_time | DATETIME | 创建时间 |
| update_time | DATETIME | 更新时间 |

### 3.6 实时地图快照表 (map_live)
| 字段 | 类型 | 说明 |
|------|------|------|
| live_id | VARCHAR(36) | 主键，固定为 `current` |
| map_id | VARCHAR(36) | 关联的静态地图 ID |
| resolution | DOUBLE | 分辨率（米/像素） |
| width | INT | 地图宽度（像素） |
| height | INT | 地图高度（像素） |
| origin_x | DOUBLE | 地图原点 X（米） |
| origin_y | DOUBLE | 地图原点 Y（米） |
| origin_yaw | DOUBLE | 地图原点偏航角（弧度） |
| grid_data | LONGTEXT | 实时栅格数据（JSON 数组） |
| obstacles | LONGTEXT | 障碍物列表（JSON） |
| update_time | DATETIME | 更新时间 |

---

## 四、核心功能说明

### 4.1 任务服务 (TaskServiceImpl)
- 任务创建（自动生成 UUID、计算动态优先级分数）
- 任务状态更新（RUNNING/SUCCESS/FAILED 自动记录时间戳与日志）
- 任务查询与筛选（支持按状态、机器人ID筛选；按 dynamic_priority_score 排序）
- **取消任务**：QUEUED 任务直接删除；RUNNING 任务标记为 FAILED
- **重新分配**：将 RUNNING 任务回退为 QUEUED，释放机器人并触发重新调度
- **调整优先级**：修改 priority 后若任务在队列中则刷新队列顺序
- **任务排行**：`TaskRankingDTO` 提供前端精简字段列表

### 4.2 机器人服务 (RobotServiceImpl)
- 机器人列表查询
- 目标点设置（内存存储 + 简化直线路径生成）
- 位姿更新（写入数据库，异步上报数据服务）
- 紧急停止

### 4.3 调度服务 (ScheduleServiceImpl)
- **优先级队列**：`PriorityBlockingQueue`，启动时加载前 1000 条 `QUEUED` 任务，按 `dynamic_priority_score` 排序
- **动态优先级**：每 30 秒自动重算所有待执行任务分数（基于基础优先级、等待时间、截止时间、任务类型权重、机器人匹配度）
- **乐观锁分配**：`UpdateWrapper` 条件更新防止并发冲突
- **调度锁**：`ReentrantLock.tryLock()` 防止调度竞态
- **故障回退**：机器人故障时，将其 `RUNNING` 任务回退为 `QUEUED` 并重新入队

### 4.4 动态优先级服务 (TaskPriorityPlannerImpl)
- 5 维评分：基础优先级、等待时间、截止时间、任务类型、最佳机器人匹配度
- 可配置权重：`scheduler.priority.weight.*`
- 自动定时重算间隔：`scheduler.priority.recalculation-interval-ms`（默认 30000ms）

### 4.5 LLM 服务 (LLMServiceImpl)
- WebSocket 连接外部 LLM 服务
- 自然语言解析为结构化子任务列表
- 每个子任务独立生成 `Task` 记录，`commandType` 为动作大写，`robotCode` 为设备编码

### 4.6 SLAM 服务 (SLAMServiceImpl)
- **标准地图格式支持**：解析 ROS `.pgm`（P5 二进制灰度图）与 `.yaml` 元数据
- **静态地图持久化**：pgm 存 MySQL `LONGBLOB`，yaml 存 `TEXT`，支持多地图记录与切换
- **实时地图快照**：RosBridge 收到 `/map` 或用户增删改障碍物时，自动将当前内存栅格（含障碍物）写入 `map_live` 表；服务重启后自动恢复
- **障碍物管理**：在原始地图之上叠加动态障碍物，支持矩形、圆形、多边形；区分实体障碍物与空气墙
- **A* 路径规划**：8 方向搜索 + 欧式距离启发函数 + 路径简化

### 4.7 ROS2 通信服务 (RosBridgeServiceImpl)
- WebSocket 客户端连接 `rosbridge_server`
- 自动订阅 `/map` → 解析后更新内存栅格地图，并**自动持久化到 `map_live`**
- 自动订阅 `/amcl_pose` → 解析位姿后更新对应机器人数据库记录
- 支持通过 `/goal_pose` 向 ROS2 发送真实导航目标
- 30 秒健康检查与 10 秒重连冷却

### 4.8 日志服务 (LogServiceImpl)
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

#### 获取任务排行（精简字段，前端专用）
```
GET /api/v1/tasks/ranking
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

#### 上传地图（PGM + YAML）
```
POST /api/v1/scheduler/slam/maps/upload
Content-Type: multipart/form-data

Request:
- mapName: 地图名称
- pgmFile: .pgm 二进制文件
- yamlFile: .yaml 文本文件

Response:
{
    "code": 200,
    "data": {
        "status": "success",
        "mapId": "xxx",
        "message": "地图上传成功"
    }
}
```

#### 切换激活地图
```
POST /api/v1/scheduler/slam/maps/{mapId}/switch

Response:
{
    "code": 200,
    "data": {
        "status": "success",
        "mapId": "xxx",
        "message": "地图切换成功"
    }
}
```

#### 获取地图列表
```
GET /api/v1/scheduler/slam/maps

Response:
{
    "code": 200,
    "data": [
        {
            "mapId": "xxx",
            "mapName": "默认地图",
            "resolution": 0.05,
            "width": 530,
            "height": 478,
            "isActive": 1,
            "createTime": "2026-04-27T10:00:00"
        }
    ]
}
```

#### 删除地图
```
DELETE /api/v1/scheduler/slam/maps/{mapId}
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

### 5.7 视觉-运动学算法通信

> 后端同时作为 WebSocket **服务端**（`/ws/vision`，接收视觉识别数据）和 **客户端**（连接运动学算法服务端）。以下 REST 接口用于手动发送目标与查询状态。

#### 发送目标物体给运动学算法端
```
POST /api/v1/motion/target
Content-Type: application/json

Request:
{
    "objName": "cup",
    "x": 1.5,
    "y": 2.0,
    "z": 0.8
}

Response:
{
    "code": 200,
    "data": {
        "sent": true,
        "objName": "cup",
        "x": 1.5,
        "y": 2.0,
        "z": 0.8
    }
}
```

#### 查询 WebSocket 连接状态
```
GET /api/v1/motion/status

Response:
{
    "code": 200,
    "data": {
        "connected": true,
        "url": "ws://localhost:8081/ws/motion",
        "messageCount": 120,
        "hasForwardResult": true,
        "hasInverseResult": true
    }
}
```

#### 查询最近一次正/逆运动学结果
```
GET /api/v1/motion/result

Response:
{
    "code": 200,
    "data": {
        "forwardKinematics": {
            "targetObj": "cup",
            "targetPose": {
                "x": 1.5,
                "y": 2.0,
                "z": 0.8,
                "roll": 0.0,
                "pitch": 0.0,
                "yaw": 0.785
            }
        },
        "inverseKinematics": {
            "targetObj": "cup",
            "ikSolve": true,
            "jointCount": 6,
            "jointValue": [0.1, 0.2, 0.3, 0.4, 0.5, 0.6]
        }
    }
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

### application.yml（示例）
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

# 运动学算法对接配置
motion:
  websocket:
    url: ws://localhost:8081/ws/motion
    timeout-ms: 10000

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
| Scheduler → 数据服务 | 关键事件后异步上报 | `POST /api/v1/tasks` 等 | 任务创建、状态变更、日志、机器人状态/位置/心跳 |
| 数据服务 → Scheduler | 外部查询/控制 | `GET /scheduler/...` | 查询队列、机器人、任务；取消/重分配任务 |

### 上报事件清单

| 事件 | 上报接口 | 位置 |
|------|---------|------|
| 创建任务 | `POST /api/v1/tasks` | `TaskServiceImpl.createTask()` |
| 任务状态变更 | `POST /api/v1/tasks/{id}/status` | `TaskServiceImpl.updateTaskStatus()` |
| 任务分配成功 | `PUT /api/v1/tasks/{id}` + 状态变更 | `ScheduleServiceImpl.tryAssignTask()` |
| 机器人状态更新 | `POST /api/v1/robots/status` | `StateTrackServiceImpl.updateRobotState()` / `RobotServiceImpl.emergencyStop()` |
| 机器人位置更新 | `POST /api/v1/navigation/positions` | `RobotServiceImpl.updateRobotPose()` |
| 机器人心跳 | `POST /api/v1/robots/heartbeat` | `DataServiceClient.reportRobotHeartbeat()` |
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
    robot_id        VARCHAR(36)     NOT NULL COMMENT '机器人ID（程序生成UUID）',
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
    task_id                 VARCHAR(36)     NOT NULL COMMENT '任务ID（程序生成UUID）',
    task_name               VARCHAR(128)    NULL COMMENT '任务名称',
    command_type            VARCHAR(64)     NULL COMMENT '指令类型',
    priority                INT             NOT NULL DEFAULT 3 COMMENT '优先级 1-5，1最高',
    robot_id                VARCHAR(36)     NULL COMMENT '分配到的机器人ID',
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
    record_id       VARCHAR(36)     NOT NULL COMMENT '记录ID（程序生成UUID）',
    task_id         VARCHAR(36)     NOT NULL COMMENT '关联任务ID',
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
    reference_id    VARCHAR(36)     NULL COMMENT '关联ID（任务ID或机器人ID）',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    PRIMARY KEY (log_id),
    INDEX idx_log_type (log_type),
    INDEX idx_reference_id (reference_id),
    INDEX idx_create_time (create_time)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='系统日志表';

-- 地图表
CREATE TABLE map (
    map_id          VARCHAR(36)     NOT NULL COMMENT '地图ID（程序生成UUID）',
    map_name        VARCHAR(100)    NOT NULL COMMENT '地图名称',
    pgm_data        LONGBLOB        NULL COMMENT 'PGM 栅格图二进制数据',
    yaml_data       TEXT            NULL COMMENT 'YAML 元数据文本',
    resolution      DOUBLE          NULL COMMENT '分辨率（米/像素）',
    origin_x        DOUBLE          NULL COMMENT '地图原点 X（米）',
    origin_y        DOUBLE          NULL COMMENT '地图原点 Y（米）',
    origin_yaw      DOUBLE          NULL COMMENT '地图原点偏航角（弧度）',
    width           INT             NULL COMMENT '地图宽度（像素）',
    height          INT             NULL COMMENT '地图高度（像素）',
    negate          INT             NULL COMMENT '是否反转像素值 0/1',
    occupied_thresh DOUBLE          NULL COMMENT '占用阈值',
    free_thresh     DOUBLE          NULL COMMENT '空闲阈值',
    is_active       TINYINT         NOT NULL DEFAULT 0 COMMENT '是否当前激活 0/1',
    create_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (map_id),
    INDEX idx_is_active (is_active)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='SLAM 地图表';

-- 实时地图快照表
CREATE TABLE map_live (
    live_id         VARCHAR(36)     NOT NULL COMMENT '实时地图ID（固定为 current）',
    map_id          VARCHAR(36)     NULL COMMENT '关联的静态地图ID',
    resolution      DOUBLE          NULL COMMENT '分辨率（米/像素）',
    width           INT             NULL COMMENT '地图宽度（像素）',
    height          INT             NULL COMMENT '地图高度（像素）',
    origin_x        DOUBLE          NULL COMMENT '地图原点 X（米）',
    origin_y        DOUBLE          NULL COMMENT '地图原点 Y（米）',
    origin_yaw      DOUBLE          NULL COMMENT '地图原点偏航角（弧度）',
    grid_data       LONGTEXT        NULL COMMENT '实时栅格数据（JSON 数组）',
    obstacles       LONGTEXT        NULL COMMENT '障碍物列表（JSON）',
    update_time     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',
    PRIMARY KEY (live_id)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci COMMENT ='SLAM 实时地图快照表';
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

> **文档版本：** v3.4.0  
> **更新日期：** 2026-04-29
