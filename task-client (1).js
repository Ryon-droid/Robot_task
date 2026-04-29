const TASK_BASE_URL = '/api/v1/tasks';
const SCHEDULER_BASE_URL = '/scheduler';

async function taskRequest(method, path, body) {
    const options = { method, headers: { 'Content-Type': 'application/json' } };
    if (body !== undefined) options.body = JSON.stringify(body);
    const res  = await fetch(`${TASK_BASE_URL}${path}`, options);
    const text = await res.text();
    console.log(`taskRequest ${method} ${TASK_BASE_URL}${path}:`, { status: res.status, response: text });
    const json = text ? JSON.parse(text) : null;
    if (!res.ok) throw new Error(json?.message ?? `HTTP ${res.status}: ${text}`);
    if (json && json.code !== 200) throw new Error(json?.message ?? '请求失败');
    const data = json.data;

// 兼容 data 是数组 or data.list
return Array.isArray(data) ? data : (data?.list ?? []);
}

async function robotApiRequest(method, path, body) {
    const options = { method, headers: { 'Content-Type': 'application/json' } };
    if (body !== undefined) options.body = JSON.stringify(body);
    const res  = await fetch(`/api/robot${path}`, options);
    const text = await res.text();
    console.log(`robotApiRequest ${method} /api/robot${path}:`, { status: res.status, response: text });
    if (!res.ok) throw new Error(`HTTP ${res.status}: ${text}`);
    // 对于机器人 API，可能没有统一的 JSON 格式，直接返回解析后的 JSON 或空对象
    try {
        const json = text ? JSON.parse(text) : {};
        return json;
    } catch (e) {
        return { success: true };
    }
}

async function schedulerRequest(method, path, body) {
    const options = { method, headers: { 'Content-Type': 'application/json' } };
    if (body !== undefined) options.body = JSON.stringify(body);
    const res  = await fetch(`${SCHEDULER_BASE_URL}${path}`, options);
    const text = await res.text();
    const json = text ? JSON.parse(text) : null;
    if (!res.ok) throw new Error(json?.message ?? `HTTP ${res.status}`);
    if (json && json.code !== 200) throw new Error(json?.message ?? '请求失败');
    const data = json.data;

// 兼容 data 是数组 or data.list
return Array.isArray(data) ? data : (data?.list ?? []);
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

            console.log('Sending task creation request:', body);
            // 创建任务返回的是对象 { taskId }, 不走 taskRequest 的列表转换
            const options = { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body) };
            const res  = await fetch(`${TASK_BASE_URL}`, options);
            const text = await res.text();
            console.log('Task creation raw response:', { status: res.status, text });
            const json = text ? JSON.parse(text) : null;
            if (!res.ok) throw new Error(json?.message ?? `HTTP ${res.status}: ${text}`);
            if (json && json.code !== 200) throw new Error(json?.message ?? '请求失败');
            const data = json.data;
            console.log('Task creation response:', data);
            this.onLog('info', `[任务] ${commandType} 创建成功，taskId: ${data?.taskId}`);
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
        return taskRequest('GET', `?${qs}`);
    }

    // ── 获取任务详情 ──────────────────────────────────────────
    // GET /api/v1/tasks/{taskId}
    async getTask(taskId) {
        return taskRequest('GET', `/${taskId}`);
    }

    // ── 更新任务状态 ──────────────────────────────────────────
    // PATCH /api/v1/tasks/{taskId}/status
    async updateTaskStatus(taskId, status, reason = null) {
        return taskRequest('PATCH', `/${taskId}/status`, { status, reason });
    }

    // ── 获取任务排行列表 ──────────────────────────────────────
    // 使用 GET /api/v1/tasks，按 dynamic_priority_score 和 priority 排序
    async getTaskRankings() {
        const tasks = await taskRequest('GET', '');
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

    // ── 设置机器人目标点 ──────────────────────────────────────
    // POST /api/robot/goal
    async setRobotGoal(robotId, x, y, yaw = 0) {
        return taskRequest('POST', '/api/robot/goal', { robotId, x, y, yaw });
    }

    // ── 获取规划路径 ──────────────────────────────────────────
    // GET /api/robot/path?robotId=r001
    async getRobotPath(robotId) {
        return robotApiRequest('GET', `/path?robotId=${robotId}`);
    }

    // ── 外部调度接口 ──────────────────────────────────────────
    // GET /scheduler/robots
    async getSchedulerRobots() {
        return schedulerRequest('GET', '/robots');
    }

    // GET /scheduler/robots/{robotId}
    async getSchedulerRobot(robotId) {
        return schedulerRequest('GET', `/robots/${robotId}`);
    }

    // GET /scheduler/tasks?status=RUNNING&robotId=xxx
    async getSchedulerTasks(robotId, status) {
        const qs = new URLSearchParams();
        if (robotId) qs.set('robotId', robotId);
        if (status)  qs.set('status', status);
        return schedulerRequest('GET', `/tasks?${qs}`);
    }

    // GET /scheduler/tasks/{taskId}
    async getSchedulerTask(taskId) {
        return schedulerRequest('GET', `/tasks/${taskId}`);
    }

    // GET /scheduler/tasks/queue
    async getTaskQueue() {
        return schedulerRequest('GET', '/tasks/queue');
    }

    // POST /scheduler/tasks/{taskId}/cancel
    async cancelTask(taskId) {
        return schedulerRequest('POST', `/tasks/${taskId}/cancel`, { taskId, action: 'cancel' });
    }

    // POST /scheduler/tasks/{taskId}/reassign
    async reassignTask(taskId) {
        return schedulerRequest('POST', `/tasks/${taskId}/reassign`, { taskId, action: 'reassign' });
    }

    // POST /scheduler/tasks/{taskId}/priority
    async updateTaskPriority(taskId, priority) {
        return schedulerRequest('POST', `/tasks/${taskId}/priority`, { priority });
    }

    // POST /scheduler/robots/{robotId}/emergency_stop
    async emergencyStopRobot(robotId) {
        return schedulerRequest('POST', `/robots/${robotId}/emergency_stop`, { robotId, action: 'emergency_stop' });
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