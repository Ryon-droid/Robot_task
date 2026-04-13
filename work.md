# Robot Scheduler 机器人调度系统

## 一、项目概述

Robot Scheduler 是一个基于 Spring Boot 的机器人任务调度系统，实现多机器人之间的任务智能分配和状态跟踪。

### 核心功能
- **任务管理**：创建、查询、状态更新
- **机器人管理**：列表查询、位置获取、目标点设置
- **日志记录**：任务完成/失败自动记录

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

### 项目结构
```
robot-scheduler/
├── src/main/java/com/robot/scheduler/
│   ├── SchedulerApplication.java          # 启动类
│   ├── common/                            # 公共类
│   │   ├── Result.java                    # 统一响应结果
│   │   └── StatusConstant.java            # 状态常量定义
│   ├── controller/                        # 控制器层
│   │   ├── NewTaskController.java         # 新任务前端API
│   │   ├── LogController.java             # 日志API
│   │   └── RobotController.java           # 机器人前端API
│   ├── entity/                            # 实体类
│   │   ├── Task.java                      # 任务实体
│   │   ├── Robot.java                     # 机器人实体
│   │   └── Log.java                       # 日志实体
│   ├── mapper/                            # MyBatis Mapper
│   │   ├── TaskMapper.java
│   │   ├── RobotMapper.java
│   │   └── LogMapper.java
│   ├── service/                           # 服务层
│   │   ├── TaskService.java               # 任务服务接口
│   │   ├── LogService.java                # 日志服务接口
│   │   ├── RobotService.java              # 机器人服务接口
│   │   └── impl/                          # 服务实现类
│   │       ├── TaskServiceImpl.java       # 任务服务实现
│   │       ├── LogServiceImpl.java        # 日志服务实现
│   │       └── RobotServiceImpl.java      # 机器人服务实现
│   ├── websocket/                         # WebSocket
│   │   └── RobotWebSocketHandler.java     # 机器人位置实时推送
│   ├── config/                            # 配置类
│   │   └── WebSocketConfig.java           # WebSocket配置
│   └── task/                              # 定时任务
│       └── RobotPositionBroadcastTask.java # 位置广播任务
├── src/main/resources/
│   ├── application.yml                    # 配置文件
│   └── mapper/                            # Mapper XML文件
├── db_init.sql                            # 数据库初始化脚本
└── pom.xml                                # Maven配置
```

---

## 三、数据库设计

### 3.1 任务表 (task)
| 字段 | 类型 | 说明 |
|------|------|------|
| task_id | VARCHAR(32) | 主键，任务ID |
| task_name | VARCHAR(64) | 任务名称 |
| command_type | VARCHAR(32) | 命令类型 |
| priority | INT | 优先级 (1-5，1最高) |
| robot_id | VARCHAR(32) | 执行机器人ID |
| robot_code | VARCHAR(32) | 机器人编码 |
| status | VARCHAR(16) | 状态：QUEUED → RUNNING → SUCCESS / FAILED |
| task_params | JSON | 任务参数 |
| create_time | DATETIME | 创建时间 |
| start_time | DATETIME | 开始时间 |
| finish_time | DATETIME | 完成时间 |
| fail_reason | VARCHAR(255) | 失败原因 |

### 3.2 机器人表 (robot)
| 字段 | 类型 | 说明 |
|------|------|------|
| robot_id | VARCHAR(32) | 主键，机器人ID |
| robot_name | VARCHAR(64) | 机器人名称 |
| status | VARCHAR(16) | 状态：空闲/忙碌/故障 |
| load | INT | 当前负载（任务数） |
| last_heartbeat | DATETIME | 最后心跳时间 |
| x | DOUBLE | X坐标（SLAM地图坐标系，单位：米） |
| y | DOUBLE | Y坐标（SLAM地图坐标系，单位：米） |
| yaw | DOUBLE | 朝向角度（弧度） |

### 3.3 日志表 (log)
| 字段 | 类型 | 说明 |
|------|------|------|
| log_id | BIGINT | 主键，自增ID |
| log_type | VARCHAR(32) | 日志类型：TASK, ROBOT, SYSTEM |
| message | TEXT | 日志内容 |
| reference_id | VARCHAR(32) | 关联ID（如任务ID、机器人ID） |
| create_time | DATETIME | 创建时间 |

---

## 四、核心功能说明

### 4.1 任务服务 (TaskServiceImpl)

#### 功能
- 任务创建与管理
- 任务状态更新
- 任务查询与筛选

#### 关键方法
```java
Task createTask(Task task)                    // 创建任务
List<Task> getTaskList(String status, String robotId)  // 获取任务列表（支持筛选）
Task getTaskById(String taskId)               // 根据ID获取任务
boolean updateTaskStatus(String taskId, String status, String reason)  // 更新任务状态
```

### 4.2 机器人服务 (RobotServiceImpl)

#### 功能
- 机器人列表查询
- 实时位置获取
- 目标点设置
- 路径规划

#### 关键方法
```java
List<Robot> getRobotList()                        // 获取机器人列表
boolean setRobotGoal(String robotId, Double x, Double y, Double yaw)  // 设置目标点
List<Map<String, Object>> getRobotPath(String robotId)  // 获取规划路径
void updateRobotPose(String robotId, Double x, Double y, Double yaw)  // 更新位置
Robot getRobotById(String robotId)                // 根据ID获取机器人
```

### 4.3 日志服务 (LogServiceImpl)

#### 功能
- 任务完成/失败日志记录
- 系统操作日志记录
- 日志查询与筛选

#### 关键方法
```java
Log createLog(String logType, String message, String referenceId)  // 创建日志
List<Log> getLogList()                        // 获取日志列表
List<Log> getLogsByType(String logType)       // 根据类型获取日志
List<Log> getLogsByReferenceId(String referenceId)  // 根据关联ID获取日志
```

---

## 五、API 接口文档

### 5.1 新任务前端接口 (NewTaskController)

#### 创建任务
```
POST /api/v1/tasks
Content-Type: application/json

Request:
{
    "robotId": "6fb3e5b6-...",
    "commandType": "MOVE_TO",
    "priority": 1,
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

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": [
        {
            "taskId": "xxx",
            "robotId": "6fb3e5b6-...",
            "robotCode": "RBT-001",
            "commandType": "MOVE_TO",
            "priority": 1,
            "status": "RUNNING",
            "createdAt": 1760000000000,
            "params": { "x": 20, "y": 8 }
        }
    ]
}
```

#### 获取任务详情
```
GET /api/v1/tasks/{taskId}

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
        "status": "RUNNING",
        "createdAt": 1760000000000,
        "params": { "x": 20, "y": 8 }
    }
}
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

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "taskId": "xxx",
        "status": "SUCCESS"
    }
}
```

### 5.2 日志接口 (LogController)

#### 获取日志列表
```
GET /api/v1/logs?type=TASK&referenceId=task001

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": [
        {
            "logId": 1,
            "type": "TASK",
            "message": "任务 task001 完成",
            "referenceId": "task001",
            "createdAt": 1760000000000
        }
    ]
}
```

### 5.3 机器人前端接口 (RobotController)

#### 获取机器人列表
```
GET /api/robots

Response:
{
    "code": 200,
    "message": "操作成功",
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

#### 实时位置更新
```
GET /api/robots/pose

Response:
{
    "code": 200,
    "message": "操作成功",
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

#### 设置机器人目标点
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

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "status": "success",
        "message": "目标点设置成功",
        "robotId": "robot001",
        "goal": {
            "x": 5.0,
            "y": 3.0,
            "yaw": 1.57
        }
    }
}
```

#### 获取规划路径
```
GET /api/robot/path?robotId=robot001

Response:
{
    "code": 200,
    "message": "操作成功",
    "data": {
        "robotId": "robot001",
        "path": [
            {"x": 1.5, "y": 2.0},
            {"x": 1.6, "y": 2.1},
            {"x": 1.7, "y": 2.2}
        ]
    }
}
```

#### WebSocket 实时推送

**连接地址**：
```
ws://localhost:8080/ws/robots
```

**推送消息格式**：
```json
{
    "type": "robot_positions",
    "timestamp": 1712736000000,
    "data": {
        "robot001": {
            "x": 1.5,
            "y": 2.0,
            "yaw": 0.785,
            "status": "空闲"
        },
        "robot002": {
            "x": 3.0,
            "y": 4.0,
            "yaw": 1.57,
            "status": "忙碌"
        }
    }
}
```

**推送频率**：每 100ms 推送一次

**前端使用示例**：
```javascript
const socket = new WebSocket('ws://localhost:8080/ws/robots');

socket.onopen = function() {
    console.log('WebSocket连接成功');
};

socket.onmessage = function(event) {
    const message = JSON.parse(event.data);
    if (message.type === 'robot_positions') {
        console.log('机器人位置更新:', message.data);
        // 更新前端显示
    }
};

socket.onclose = function() {
    console.log('WebSocket连接关闭');
};
```

---

## 六、状态常量定义

```java
// 机器人状态
ROBOT_STATUS_IDLE   = "空闲"
ROBOT_STATUS_BUSY   = "忙碌"
ROBOT_STATUS_ERROR  = "故障"

// 任务状态
TASK_STATUS_QUEUED    = "QUEUED"    // 队列中
TASK_STATUS_RUNNING   = "RUNNING"   // 执行中
TASK_STATUS_SUCCESS   = "SUCCESS"   // 成功
TASK_STATUS_FAILED    = "FAILED"    // 失败

// 任务优先级
PRIORITY_HIGHEST = 1   // 最高
PRIORITY_HIGH    = 2   // 高
PRIORITY_NORMAL  = 3   // 正常
PRIORITY_LOW     = 4   // 低
PRIORITY_LOWEST  = 5   // 最低
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
```

---

## 八、数据库初始化

执行 `db_init.sql` 创建数据库和表：

```sql
-- 创建数据库
CREATE DATABASE robot_scheduler DEFAULT CHARACTER SET utf8mb4;
USE robot_scheduler;

-- 机器人表
CREATE TABLE robot (
    robot_id VARCHAR(32) PRIMARY KEY,
    robot_name VARCHAR(64) NOT NULL,
    status VARCHAR(16) DEFAULT '空闲',
    load INT DEFAULT 0,
    last_heartbeat DATETIME,
    x DOUBLE,
    y DOUBLE,
    yaw DOUBLE
);

-- 任务表
CREATE TABLE task (
    task_id VARCHAR(32) PRIMARY KEY,
    task_name VARCHAR(64) NOT NULL,
    command_type VARCHAR(32),
    priority INT DEFAULT 3,
    robot_id VARCHAR(32),
    robot_code VARCHAR(32),
    status VARCHAR(16) DEFAULT 'QUEUED',
    task_params JSON,
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP,
    start_time DATETIME,
    finish_time DATETIME,
    fail_reason VARCHAR(255)
);

-- 日志表
CREATE TABLE log (
    log_id BIGINT AUTO_INCREMENT PRIMARY KEY,
    log_type VARCHAR(32),
    message TEXT,
    reference_id VARCHAR(32),
    create_time DATETIME DEFAULT CURRENT_TIMESTAMP
);
```

---

## 九、启动方式

```bash
# 1. 创建数据库并执行 db_init.sql

# 2. 修改 application.yml 中的数据库配置

# 3. 启动应用
mvn spring-boot:run

# 4. 访问 API
http://localhost:8080
```