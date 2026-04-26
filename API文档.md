# Robot Scheduler 后端服务接口文档

> 本文档面向前端开发者，汇总 Robot Scheduler 机器人调度系统的 REST API 接口与数据格式。

---

## 1. 系统概述

Robot Scheduler 是基于 **Spring Boot 2.7.18** 的机器人任务调度后端服务，负责：

- 多机器人任务分配与状态跟踪
- 任务生命周期管理（创建 → 排队 → 执行 → 完成/失败）
- 机器人实时位姿管理与目标点下发
- **动态优先级调度**（等待时间、截止时间、任务类型、机器人匹配度）
- **LLM 自然语言指令解析**与自动子任务拆分
- **SLAM 地图管理**（OccupancyGrid、障碍物、A* 路径规划）
- **ROS2 通信对接**（通过 rosbridge_suite 实时接收地图/位姿，下发导航目标）
- 调度日志记录

**默认访问地址：** `http://localhost:8080`

**位姿更新方式：**
- 前端通过定时轮询 `GET /api/robots/pose` 获取机器人实时位姿
- 后端通过 rosbridge 自动订阅 `/amcl_pose` 更新数据库，前端轮询即可获取最新数据

---

## 2. 技术栈

| 组件 | 技术/版本 |
|------|-----------|
| 语言 / JDK | Java 17 |
| 构建工具 | Maven 3.x |
| 主框架 | Spring Boot 2.7.18 |
| Web | Spring Boot Starter Web |
| WebSocket | Spring Boot Starter WebSocket |
| ORM | MyBatis-Plus 3.5.3.1 |
| 数据库 | MySQL 8.0 |
| JSON | Jackson Databind |
| 工具类 | Lombok |

---

## 3. 数据库设计

执行 `db_init.sql` 初始化数据库 `robot_scheduler`。

### 3.1 机器人表 `robot`

| 字段 | 类型 | 说明 |
|------|------|------|
| `robot_id` | VARCHAR(32) PK | 主键，代码生成 UUID（无横线） |
| `robot_name` | VARCHAR(64) | 机器人名称 |
| `robot_code` | VARCHAR(32) UNIQUE | 机器人编码，用于与 ROS / LLM 对接 |
| `status` | VARCHAR(16) | 状态：`空闲` / `忙碌` / `故障` |
| `load` | INT DEFAULT 0 | 当前负载任务数 |
| `battery` | INT DEFAULT 100 | 电量百分比 |
| `x` | DOUBLE | X 坐标（米，SLAM 地图坐标系） |
| `y` | DOUBLE | Y 坐标（米） |
| `yaw` | DOUBLE | 朝向角度（弧度） |
| `last_heartbeat` | DATETIME | 最后心跳时间 |

### 3.2 任务表 `task`

| 字段 | 类型 | 说明 |
|------|------|------|
| `task_id` | VARCHAR(32) PK | 主键，代码生成 UUID（无横线） |
| `task_name` | VARCHAR(64) | 任务名称 |
| `command_type` | VARCHAR(32) | 命令类型 |
| `priority` | INT DEFAULT 3 | 优先级 1~5（1 最高） |
| `robot_id` | VARCHAR(32) | 分配到的机器人 ID |
| `robot_code` | VARCHAR(32) | 机器人编码 |
| `status` | VARCHAR(16) DEFAULT 'QUEUED' | 任务状态 |
| `task_params` | JSON | 结构化任务参数 |
| `create_time` | DATETIME | 创建时间 |
| `start_time` | DATETIME | 开始执行时间 |
| `finish_time` | DATETIME | 完成/失败时间 |
| `fail_reason` | VARCHAR(255) | 失败原因 |
| `deadline` | DATETIME | 任务截止时间（可选） |
| `estimated_duration` | INT DEFAULT 0 | 预估执行时长（秒） |
| `dynamic_priority_score` | DOUBLE DEFAULT 0 | 动态优先级分数（越低越优先） |

### 3.3 任务状态记录表 `task_record`

| 字段 | 类型 | 说明 |
|------|------|------|
| `record_id` | VARCHAR(32) PK | 主键，UUID |
| `task_id` | VARCHAR(32) | 关联任务 ID |
| `old_status` | VARCHAR(16) | 变更前状态 |
| `new_status` | VARCHAR(16) | 变更后状态 |
| `change_time` | DATETIME | 变更时间 |
| `change_reason` | VARCHAR(255) | 变更原因 |

### 3.4 日志表 `log`

| 字段 | 类型 | 说明 |
|------|------|------|
| `log_id` | BIGINT AUTO_INCREMENT PK | 自增主键 |
| `log_type` | VARCHAR(32) | 日志类型：`TASK` / `ROBOT` / `SYSTEM` |
| `message` | TEXT | 日志内容 |
| `reference_id` | VARCHAR(32) | 关联 ID（任务 ID / 机器人 ID） |
| `create_time` | DATETIME | 创建时间 |

---

## 4. 统一响应协议

**所有 REST 接口均返回统一 JSON 结构：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": { }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `code` | int | 状态码。200 成功；500 系统异常；其他自定义业务码 |
| `message` | string | 响应描述信息 |
| `data` | T | 业务数据，类型随接口变化 |

---

## 5. 状态常量与定义

### 5.1 机器人状态

| 常量名 | 值 | 说明 |
|--------|-----|------|
| `ROBOT_STATUS_IDLE` | `空闲` | 可接受新任务 |
| `ROBOT_STATUS_BUSY` | `忙碌` | 正在执行任务 |
| `ROBOT_STATUS_ERROR` | `故障` | 发生故障，需人工处理 |

### 5.2 任务状态

| 常量名 | 值 | 说明 |
|--------|-----|------|
| `TASK_STATUS_PENDING` | `QUEUED` | 待执行 |
| `TASK_STATUS_RUNNING` | `RUNNING` | 执行中 |
| `TASK_STATUS_COMPLETED` | `SUCCESS` | 已完成 |
| `TASK_STATUS_FAILED` | `FAILED` | 执行失败 |

### 5.3 任务优先级

| 常量名 | 值 |
|--------|-----|
| `PRIORITY_HIGHEST` | 1 |
| `PRIORITY_HIGH` | 2 |
| `PRIORITY_NORMAL` | 3 |
| `PRIORITY_LOW` | 4 |
| `PRIORITY_LOWEST` | 5 |

---

## 6. REST API 接口详解

---

### 6.1 任务管理 API

**基地址：** `/api/v1/tasks`

#### 6.1.1 创建任务

- **URL：** `POST /api/v1/tasks`
- **说明：** 创建任务后自动触发调度器尝试分配；支持 `deadline` 和 `estimatedDuration` 用于动态优先级计算
- **请求体：**

```json
{
  "robotId": "6fb3e5b6-...",
  "commandType": "MOVE_TO",
  "priority": 1,
  "estimatedDuration": 120,
  "deadline": "2026-04-27T10:00:00",
  "params": {
    "x": 20,
    "y": 8,
    "speed": 1.5
  }
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 否 | 指定执行机器人（可选） |
| `commandType` | string | 是 | 命令类型 |
| `priority` | int | 否 | 优先级 1~5，默认 3（1 最高） |
| `estimatedDuration` | int | 否 | 预估执行时长（秒），用于优先级计算 |
| `deadline` | string/number | 否 | 截止时间。支持 ISO 8601 字符串或毫秒时间戳 |
| `params` | object | 否 | 任务参数，任意 JSON |

- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "taskId": "a1b2c3d4e5f6...",
    "robotId": "6fb3e5b6-...",
    "robotCode": "RBT-001",
    "commandType": "MOVE_TO",
    "priority": 1,
    "status": "QUEUED",
    "createdAt": 1760000000000,
    "params": {
      "x": 20,
      "y": 8,
      "speed": 1.5
    }
  }
}
```

#### 6.1.2 获取任务列表

- **URL：** `GET /api/v1/tasks?status=RUNNING&robotId=xxx`
- **说明：** 支持按状态和机器人 ID 筛选；不传参则返回全部。按 `dynamic_priority_score` 和 `priority` 排序
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "taskId": "a1b2c3...",
      "robotId": "6fb3e5b6-...",
      "robotCode": "RBT-001",
      "commandType": "MOVE_TO",
      "priority": 1,
      "status": "RUNNING",
      "createdAt": 1760000000000,
      "params": {
        "x": 20,
        "y": 8
      }
    }
  ]
}
```

#### 6.1.3 获取任务详情

- **URL：** `GET /api/v1/tasks/{taskId}`
- **响应示例：** 同列表单项

#### 6.1.4 更新任务状态

- **URL：** `PATCH /api/v1/tasks/{taskId}/status`
- **说明：** 更新为 `SUCCESS` 或 `FAILED` 时，自动写入 `log` 表
- **请求体：**

```json
{
  "status": "SUCCESS",
  "reason": null
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `status` | string | 是 | `RUNNING` / `SUCCESS` / `FAILED` |
| `reason` | string | 否 | 失败原因，`FAILED` 时建议填写 |

- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "taskId": "a1b2c3...",
    "status": "SUCCESS"
  }
}
```

---

### 6.2 机器人管理 API

**基地址：** `/api`

#### 6.2.1 获取机器人列表

- **URL：** `GET /api/robots`
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": "r001",
      "name": "Robot-A",
      "x": 1.25,
      "y": 3.50,
      "status": "空闲"
    }
  ]
}
```

#### 6.2.2 获取机器人实时位姿

- **URL：** `GET /api/robots/pose`
- **说明：** 返回 x, y, yaw。若已配置 rosbridge，位姿由 ROS2 `/amcl_pose` 自动同步到数据库
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "id": "r001",
      "x": 1.25,
      "y": 3.50,
      "yaw": 0.785
    }
  ]
}
```

#### 6.2.3 设置机器人目标点

- **URL：** `POST /api/robot/goal`
- **说明：** 在内存保存目标点，并生成简化直线路径（每 0.1m 插值）。如需真正下发到 ROS2，请使用 `/api/v1/scheduler/ros/goal`
- **请求体：**

```json
{
  "robotId": "r001",
  "x": 5.0,
  "y": 10.0,
  "yaw": 1.57
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotId` | string | 是 | 机器人 ID |
| `x` | double | 是 | 目标 X 坐标（米） |
| `y` | double | 是 | 目标 Y 坐标（米） |
| `yaw` | double | 否 | 目标朝向（弧度），默认 0 |

- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "status": "success",
    "message": "目标点设置成功",
    "robotId": "r001",
    "goal": {
      "x": 5.0,
      "y": 10.0,
      "yaw": 1.57
    }
  }
}
```

#### 6.2.4 获取规划路径

- **URL：** `GET /api/robot/path?robotId=r001`
- **说明：** 返回当前内存中存储的路径（简化直线插值）
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "robotId": "r001",
    "path": [
      { "x": 1.25, "y": 3.50 },
      { "x": 1.35, "y": 3.60 },
      { "x": 1.45, "y": 3.70 }
    ]
  }
}
```

---

### 6.3 日志查询 API

**基地址：** `/api/v1/logs`

#### 6.3.1 获取日志列表

- **URL：** `GET /api/v1/logs?type=TASK&referenceId=t001`
- **说明：** 同时传 `type` 和 `referenceId` 时，优先按 `type` 筛选
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "logId": 1,
      "type": "TASK",
      "message": "任务 t001 完成",
      "referenceId": "t001",
      "createdAt": 1713946000000
    }
  ]
}
```

> 任务完成或失败时，系统会自动写入 `TASK` 类型日志，前端无需额外调用即可在日志面板展示任务结果。

---

### 6.4 SLAM 地图与路径规划 API

**基地址：** `/api/v1/scheduler/slam`

> 地图数据默认保存在内存中，服务重启后丢失。若已配置 rosbridge，地图数据可由 ROS2 `/map` 自动同步更新。

#### 6.4.1 获取地图数据

- **URL：** `GET /api/v1/scheduler/slam/map`
- **说明：** 返回 OccupancyGrid 格式地图，含障碍物列表
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "resolution": 0.05,
    "width": 400,
    "height": 400,
    "origin": { "x": -10.0, "y": -10.0 },
    "data": [0, 0, 0, 100, 100, ...],
    "obstacles": [
      {
        "id": "obs-001",
        "type": "obstacle",
        "shape": "rectangle",
        "x": 5.0,
        "y": 3.0,
        "width": 2.0,
        "height": 1.0
      },
      {
        "id": "obs-002",
        "type": "invisible",
        "shape": "circle",
        "x": 8.0,
        "y": 6.0,
        "radius": 1.5
      }
    ]
  }
}
```

| 字段 | 类型 | 说明 |
|------|------|------|
| `resolution` | double | 地图分辨率（米/像素） |
| `width` / `height` | int | 地图像素尺寸 |
| `origin` | object | 地图原点世界坐标（左下角） |
| `data` | int[] | 栅格数组，长度 = width × height；0=空闲，100=障碍，-1=未知 |
| `obstacles` | array | 障碍物/空气墙列表 |

#### 6.4.2 更新/上传地图数据

- **URL：** `POST /api/v1/scheduler/slam/map`
- **请求体：**

```json
{
  "resolution": 0.05,
  "width": 400,
  "height": 400,
  "origin": { "x": -10.0, "y": -10.0 },
  "data": [0, 0, 0, 100, 100, ...]
}
```

#### 6.4.3 重置地图

- **URL：** `POST /api/v1/scheduler/slam/map/reset`
- **说明：** 清空为默认空地图（20m×20m），并删除所有障碍物

#### 6.4.4 获取障碍物列表

- **URL：** `GET /api/v1/scheduler/slam/obstacles`
- **说明：** 返回所有障碍物和空气墙

#### 6.4.5 添加障碍物 / 空气墙

- **URL：** `POST /api/v1/scheduler/slam/obstacles`
- **请求体：**

```json
{
  "type": "obstacle",
  "shape": "rectangle",
  "x": 5.0,
  "y": 3.0,
  "width": 2.0,
  "height": 1.0
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `type` | string | 否 | `obstacle`（实体障碍）或 `invisible`（空气墙），默认 `obstacle` |
| `shape` | string | 否 | `rectangle` / `circle` / `polygon`，默认 `rectangle` |
| `x` / `y` | double | 是 | 中心点世界坐标（米） |
| `width` / `height` | double | 否 | 矩形宽高（米），矩形时必填 |
| `radius` | double | 否 | 圆半径（米），圆形时必填 |
| `points` | array | 否 | 多边形顶点 `[{x, y}, ...]`，多边形时必填且 ≥3 点 |

#### 6.4.6 修改障碍物

- **URL：** `PUT /api/v1/scheduler/slam/obstacles/{obstacleId}`
- **请求体：** 同添加，会替换原障碍物全部属性

#### 6.4.7 删除障碍物

- **URL：** `DELETE /api/v1/scheduler/slam/obstacles/{obstacleId}`

#### 6.4.8 手动标点规划路径（A*）

- **URL：** `POST /api/v1/scheduler/slam/path/plan`
- **说明：** 在地图上给定起点和终点，返回 A* 规划路径（已做简单平滑）
- **请求体：**

```json
{
  "startX": 0.0,
  "startY": 0.0,
  "goalX": 10.0,
  "goalY": 8.0
}
```

- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    { "x": 0.0, "y": 0.0 },
    { "x": 2.5, "y": 2.0 },
    { "x": 5.0, "y": 4.0 },
    { "x": 10.0, "y": 8.0 }
  ]
}
```

> 路径规划会同时考虑**原始地图障碍**和**用户添加的障碍物/空气墙**。地图外区域视为不可通行。

---

### 6.5 LLM 自然语言解析 API

**基地址：** `/api/v1/scheduler/llm`

#### 6.5.1 解析自然语言指令

- **URL：** `POST /api/v1/scheduler/llm/parse`
- **说明：** 调用外部 LLM 服务，将自然语言指令拆分为结构化子任务列表
- **请求体：**

```json
{
  "instruction": "让机器人A去会议室拿杯子"
}
```

- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": [
    {
      "taskId": "a1b2c3...",
      "taskName": "杯子-机器人A-GRAB",
      "commandType": "GRAB",
      "robotCode": "机器人A",
      "priority": 3,
      "status": "QUEUED",
      "params": {
        "id": 1,
        "device": "机器人A",
        "action": "grab",
        "target": "杯子",
        "condition": "在会议室",
        "fail_handler": "通知管理员"
      }
    }
  ]
}
```

> 每个子任务会独立生成 `Task` 记录存入数据库，并自动进入调度队列。

---

### 6.6 ROS2 通信 API

**基地址：** `/api/v1/scheduler/ros`

> 后端启动时自动连接 `rosbridge_server`（默认 `ws://localhost:9090`），并订阅 `/map` 和 `/amcl_pose`。以下接口用于查询状态和手动下发导航目标。

#### 6.6.1 查询 rosbridge 连接状态

- **URL：** `GET /api/v1/scheduler/ros/status`
- **响应示例：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "connected": true,
    "url": "ws://localhost:9090",
    "mapTopic": "/map",
    "poseTopic": "/amcl_pose",
    "goalTopic": "/goal_pose",
    "mapMessageCount": 120,
    "poseMessageCount": 500
  }
}
```

#### 6.6.2 发送导航目标到 ROS2

- **URL：** `POST /api/v1/scheduler/ros/goal`
- **说明：** 向 ROS2 发布 `/goal_pose`，同时更新内存目标点和 Mock 路径
- **请求体：**

```json
{
  "robotCode": "tb3_0",
  "x": 5.0,
  "y": 3.0,
  "yaw": 0.0
}
```

| 字段 | 类型 | 必填 | 说明 |
|------|------|------|------|
| `robotCode` | string | 否 | 机器人编码。空字符串时使用配置的 `default-robot-code` |
| `x` | double | 是 | 目标 X 坐标（米） |
| `y` | double | 是 | 目标 Y 坐标（米） |
| `yaw` | double | 否 | 目标朝向（弧度），默认 0 |

- **响应示例（成功）：**

```json
{
  "code": 200,
  "message": "操作成功",
  "data": {
    "sent": true,
    "robotCode": "tb3_0",
    "x": 5.0,
    "y": 3.0,
    "yaw": 0.0
  }
}
```

- **响应示例（失败）：**

```json
{
  "code": 500,
  "message": "导航目标发送失败，请检查 rosbridge 连接状态"
}
```

---

## 7. 核心调度机制

### 7.1 调度流程

```
┌─────────────────────┐
│  PriorityBlockingQueue │  ← 按 dynamicPriorityScore 升序 + createTime 升序
│   (待执行任务队列)      │
└──────────┬──────────┘
           │ triggerSchedule()
           ▼
    ┌──────────────┐
    │ 获取空闲机器人 │ ← status='空闲'，按 load 升序取第一个
    └──────┬───────┘
           │
           ▼
    ┌────────────────────┐
    │ 乐观锁更新任务状态   │ ← eq("status", "QUEUED") → set("status", "RUNNING")
    │ 乐观锁更新机器人状态 │ ← eq("status", "空闲") → set("status", "忙碌"), load+1
    └──────┬─────────────┘
           │
           ▼
    ┌────────────────────┐
    │ 写入 task_record    │ ← 记录状态流转
    │ 写 log（任务结果）   │
    └────────────────────┘
```

### 7.2 动态优先级算法

`TaskPriorityPlannerImpl` 每 30 秒自动重算所有 `QUEUED` 任务的 `dynamic_priority_score`：

```
total = baseWeight × priority × 10
      + waitingWeight × min(等待分钟数, 30)
      + deadlineWeight × deadlineScore
      + typeWeight × commandTypeWeight
      + robotWeight × robotMatchScore
```

| 因子 | 说明 |
|------|------|
| **基础优先级** | `priority × 10`，priority 越小分值越低（越优先） |
| **等待时间** | 每分钟 +1，封顶 30，防止任务饿死 |
| **截止时间** | 已过期 +100；1 小时内按小时线性递减（最高 +50）；否则 0 |
| **任务类型** | 可配置权重（如 CHARGE=5, NAVIGATE=10, GRAB=15） |
| **机器人匹配** | 到最近空闲机器人的欧氏距离 + 电量惩罚 `(100-battery)/10` |

### 7.3 关键设计点

| 设计 | 说明 |
|------|------|
| **优先级队列** | `PriorityBlockingQueue`，启动时从 DB 加载前 1000 条 `QUEUED` 任务，按 `dynamicPriorityScore` 排序 |
| **动态优先级** | 每 30 秒自动重算所有 `QUEUED` 任务分数（基于等待时间、截止时间、任务类型、机器人匹配度） |
| **乐观锁** | `UpdateWrapper` 带 `eq("status", oldStatus)` 条件更新，防止并发冲突 |
| **故障回退** | 机器人故障时，将其 `RUNNING` 任务回退为 `QUEUED` 并重新入队，机器人设为 `故障` |
| **调度锁** | `ReentrantLock.tryLock()` 防止调度竞态 |
| **事务** | `tryAssignTask` 与 `handleRobotError` 均标注 `@Transactional` |

---

## 8. 配置参数

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
```

| 配置项 | 说明 |
|--------|------|
| `scheduler.priority.weight.*` | 动态优先级 5 个因子的权重 |
| `scheduler.priority.recalculation-interval-ms` | 优先级自动重算间隔（毫秒） |
| `llm.websocket.url` | 外部 LLM 服务 WebSocket 地址 |
| `llm.websocket.timeout-ms` | LLM 请求超时（毫秒） |
| `rosbridge.websocket.url` | rosbridge_server WebSocket 地址 |
| `rosbridge.topics.map` | 地图话题，默认 `/map` |
| `rosbridge.topics.pose` | 位姿话题，默认 `/amcl_pose` |
| `rosbridge.topics.goal` | 导航目标话题，默认 `/goal_pose` |
| `rosbridge.default-robot-code` | 默认机器人编码，用于位姿更新映射 |

---

## 9. 已知问题与注意事项

1. **机器人路径规划为 Mock**  
   `RobotServiceImpl.generatePath()` 仅做起点到终点的线性插值（每 0.1m 一个点），非真实导航算法。SLAM 模块中的 `POST /slam/path/plan` 已实现基于栅格地图的 A* 算法。

2. **SLAM 数据默认存内存**  
   地图与障碍物数据保存在内存中，服务重启后丢失。若已启动 ROS2 侧的 `rosbridge_server`，后端会自动订阅 `/map` 重新加载地图。

3. **CORS 全开放**  
   `CorsConfig` 允许所有来源、所有方法、携带凭证，生产环境需收紧。

4. **无单元测试**  
   项目中不存在 `src/test` 目录或任何测试类。

5. **机器人回调接口缺失**  
   机器人完成/失败任务后通知后端的回调接口尚未实现。当前任务状态更新依赖前端或管理员手动调用 `PATCH /api/v1/tasks/{taskId}/status`。

---

> **文档版本：** v3.0.0  
> **更新日期：** 2026-04-26
