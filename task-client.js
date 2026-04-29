// task-client.js — 对接后端 B（任务调度服务）
// 根据后端 B API 文档实现

const TASK_BASE_URL = 'http://172.16.25.79:8080';

async function taskRequest(method, path, body) {
    const options = { method, headers: { 'Content-Type': 'application/json' } };
    if (body !== undefined) options.body = JSON.stringify(body);
    const res  = await fetch(`${TASK_BASE_URL}${path}`, options);
    const text = await res.text();
    const json = text ? JSON.parse(text) : null;
    if (!res.ok) throw new Error(json?.message ?? `HTTP ${res.status}`);
    if (json && json.code !== 200) throw new Error(json?.message ?? '请求失败');
    return json.data;
}

export class TaskClient {
    constructor(onLog) {
        this.onLog = onLog;
    }

    // ── 创建任务 ──────────────────────────────────────────────
    // POST /api/v1/tasks
    async createTask(robotId, commandType, priority = 3, params = {}, estimatedDuration = null, deadline = null) {
        this.onLog('info', `[任务] 创建中：${commandType} P${priority}`);
        try {
            const body = {
                robotId, commandType, priority, params,
            };
            if (estimatedDuration !== null) body.estimatedDuration = estimatedDuration;
            if (deadline !== null) body.deadline = deadline;
            const data = await taskRequest('POST', '/api/v1/tasks', body);
            this.onLog('info', `[任务] 创建成功，taskId: ${data.taskId}`);
            return data;
        } catch(e) {
            this.onLog('error', `[任务] 创建失败: ${e.message}`);
            throw e;
        }
    }

    // ── 获取任务列表 ──────────────────────────────────────────
    // GET /api/v1/tasks?status=RUNNING&robotId=xxx
    async getTasks(robotId, status) {
        const qs = new URLSearchParams();
        if (robotId) qs.set('robotId', robotId);
        if (status)  qs.set('status', status);
        return taskRequest('GET', `/api/v1/tasks?${qs}`);
    }

    // ── 获取任务详情 ──────────────────────────────────────────
    // GET /api/v1/tasks/{taskId}
    async getTask(taskId) {
        return taskRequest('GET', `/api/v1/tasks/${taskId}`);
    }

    // ── 更新任务状态 ──────────────────────────────────────────
    // PATCH /api/v1/tasks/{taskId}/status
    async updateTaskStatus(taskId, status, reason = null) {
        return taskRequest('PATCH', `/api/v1/tasks/${taskId}/status`, { status, reason });
    }

    // ── 获取任务排行列表 ──────────────────────────────────────
    // 使用 GET /api/v1/tasks，按 dynamic_priority_score 和 priority 排序
    async getTaskRankings() {
        const tasks = await taskRequest('GET', '/api/v1/tasks');
        // 假设后端已排序，如需前端排序，可添加逻辑
        return tasks;
    }

    // ── 获取机器人列表 ────────────────────────────────────────
    // GET /api/robots
    async getRobots() {
        return taskRequest('GET', '/api/robots');
    }

    // ── 获取机器人实时位姿 ────────────────────────────────────
    // GET /api/robots/pose
    async getRobotPoses() {
        return taskRequest('GET', '/api/robots/pose');
    }

    // ── 更新机器人实时位姿（前端上报坐标）────────────────────────
    // POST /api/robots/pose
    async updateRobotPose(robotId, x, y, yaw = 0) {
        return taskRequest('POST', '/api/robots/pose', { robotId, x, y, yaw });
    }

    // ── 设置机器人目标点 ──────────────────────────────────────
    // POST /api/robot/goal
    async setRobotGoal(robotId, x, y, yaw = 0) {
        return taskRequest('POST', '/api/robot/goal', { robotId, x, y, yaw });
    }

    // ── 获取规划路径 ──────────────────────────────────────────
    // GET /api/robot/path?robotId=r001
    async getRobotPath(robotId) {
        return taskRequest('GET', `/api/robot/path?robotId=${robotId}`);
    }

    // ── 外部调度接口 ──────────────────────────────────────────
    // GET /scheduler/robots
    async getSchedulerRobots() {
        return taskRequest('GET', '/scheduler/robots');
    }

    // GET /scheduler/robots/{robotId}
    async getSchedulerRobot(robotId) {
        return taskRequest('GET', `/scheduler/robots/${robotId}`);
    }

    // GET /scheduler/tasks?status=RUNNING&robotId=xxx
    async getSchedulerTasks(robotId, status) {
        const qs = new URLSearchParams();
        if (robotId) qs.set('robotId', robotId);
        if (status)  qs.set('status', status);
        return taskRequest('GET', `/scheduler/tasks?${qs}`);
    }

    // GET /scheduler/tasks/{taskId}
    async getSchedulerTask(taskId) {
        return taskRequest('GET', `/scheduler/tasks/${taskId}`);
    }

    // GET /scheduler/tasks/queue
    async getTaskQueue() {
        return taskRequest('GET', '/scheduler/tasks/queue');
    }

    // POST /scheduler/tasks/{taskId}/cancel
    async cancelTask(taskId) {
        return taskRequest('POST', `/scheduler/tasks/${taskId}/cancel`, { taskId, action: 'cancel' });
    }

    // POST /scheduler/tasks/{taskId}/reassign
    async reassignTask(taskId) {
        return taskRequest('POST', `/scheduler/tasks/${taskId}/reassign`, { taskId, action: 'reassign' });
    }

    // POST /scheduler/tasks/{taskId}/priority
    async updateTaskPriority(taskId, priority) {
        return taskRequest('POST', `/scheduler/tasks/${taskId}/priority`, { priority });
    }

    // POST /scheduler/robots/{robotId}/emergency_stop
    async emergencyStopRobot(robotId) {
        return taskRequest('POST', `/scheduler/robots/${robotId}/emergency_stop`, { robotId, action: 'emergency_stop' });
    }

    // ── 日志查询 ──────────────────────────────────────────────
    // GET /api/v1/logs?type=TASK&referenceId=t001
    async getLogs(type, referenceId) {
        const qs = new URLSearchParams();
        if (type) qs.set('type', type);
        if (referenceId) qs.set('referenceId', referenceId);
        return taskRequest('GET', `/api/v1/logs?${qs}`);
    }
}
