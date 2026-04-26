package com.robot.scheduler.service.impl;

import com.robot.scheduler.service.SLAMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.*;

@Slf4j
@Service
public class SLAMServiceImpl implements SLAMService {

    // ==================== 地图数据 ====================

    /** 地图分辨率（米/像素） */
    private double resolution = 0.05;

    /** 地图宽度（像素） */
    private int width = 0;

    /** 地图高度（像素） */
    private int height = 0;

    /** 地图原点（世界坐标） */
    private double originX = 0.0;
    private double originY = 0.0;

    /** 栅格数据：0=空闲，100=障碍，-1=未知 */
    private int[] gridData = new int[0];

    /** 是否正在建图 */
    private boolean isMapping = false;

    // ==================== 障碍物 / 空气墙 ====================

    private final List<Map<String, Object>> obstacles = new ArrayList<>();

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        // 默认创建一个 20m x 20m 的空地图
        resetMapInternal(20.0, 20.0, 0.05, -10.0, -10.0);
        log.info("SLAM 地图初始化完成：{}m x {}m，分辨率 {}", 20.0, 20.0, 0.05);
    }

    // ==================== 地图管理 ====================

    @Override
    public Map<String, Object> getMapData() {
        Map<String, Object> result = new HashMap<>();
        result.put("resolution", resolution);
        result.put("width", width);
        result.put("height", height);
        result.put("origin", Map.of("x", originX, "y", originY));
        result.put("data", gridData);
        result.put("obstacles", new ArrayList<>(obstacles));
        return result;
    }

    @Override
    public Map<String, Object> updateMapData(Map<String, Object> mapData) {
        if (mapData.containsKey("resolution")) {
            this.resolution = parseDouble(mapData.get("resolution"), 0.05);
        }
        if (mapData.containsKey("width")) {
            this.width = parseInt(mapData.get("width"), 0);
        }
        if (mapData.containsKey("height")) {
            this.height = parseInt(mapData.get("height"), 0);
        }
        if (mapData.containsKey("origin")) {
            Map<String, Object> origin = castToMap(mapData.get("origin"));
            if (origin != null) {
                this.originX = parseDouble(origin.get("x"), 0.0);
                this.originY = parseDouble(origin.get("y"), 0.0);
            }
        }
        if (mapData.containsKey("data")) {
            this.gridData = parseIntArray(mapData.get("data"));
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "更新地图数据成功");
        return result;
    }

    @Override
    public Map<String, Object> resetMap() {
        resetMapInternal(20.0, 20.0, 0.05, -10.0, -10.0);
        obstacles.clear();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "重置地图成功");
        return result;
    }

    private void resetMapInternal(double mapWidthMeters, double mapHeightMeters,
                                   double res, double ox, double oy) {
        this.resolution = res;
        this.width = (int) Math.ceil(mapWidthMeters / res);
        this.height = (int) Math.ceil(mapHeightMeters / res);
        this.originX = ox;
        this.originY = oy;
        this.gridData = new int[this.width * this.height];
        Arrays.fill(this.gridData, 0); // 全部空闲
        this.isMapping = false;
    }

    @Override
    public Map<String, Object> getMapStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("isMapping", isMapping);
        result.put("width", width);
        result.put("height", height);
        result.put("resolution", resolution);
        result.put("origin", Map.of("x", originX, "y", originY));
        result.put("obstacleCount", obstacles.size());
        return result;
    }

    // ==================== 障碍物 / 空气墙 ====================

    @Override
    public List<Map<String, Object>> getObstacles() {
        return new ArrayList<>(obstacles);
    }

    @Override
    public Map<String, Object> addObstacle(Map<String, Object> obstacleData) {
        String obstacleId = UUID.randomUUID().toString();
        obstacleData.put("id", obstacleId);

        // 默认类型为 obstacle（实体障碍物），可传 invisible 表示空气墙
        if (!obstacleData.containsKey("type")) {
            obstacleData.put("type", "obstacle");
        }

        // 默认形状 rectangle
        if (!obstacleData.containsKey("shape")) {
            obstacleData.put("shape", "rectangle");
        }

        obstacles.add(obstacleData);
        applyObstaclesToGrid();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("obstacleId", obstacleId);
        return result;
    }

    @Override
    public Map<String, Object> removeObstacle(String obstacleId) {
        boolean removed = obstacles.removeIf(o -> obstacleId.equals(o.get("id")));
        if (removed) {
            applyObstaclesToGrid();
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", removed ? "success" : "error");
        result.put("message", removed ? "删除成功" : "障碍物不存在");
        return result;
    }

    @Override
    public Map<String, Object> updateObstacle(String obstacleId, Map<String, Object> obstacleData) {
        for (Map<String, Object> obstacle : obstacles) {
            if (obstacleId.equals(obstacle.get("id"))) {
                obstacleData.put("id", obstacleId);
                obstacle.clear();
                obstacle.putAll(obstacleData);
                applyObstaclesToGrid();

                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                return result;
            }
        }

        Map<String, Object> result = new HashMap<>();
        result.put("status", "error");
        result.put("message", "障碍物不存在");
        return result;
    }

    /**
     * 将所有障碍物（含空气墙）标记到栅格地图上
     */
    private void applyObstaclesToGrid() {
        // 先清空原有障碍标记（保留原始地图数据）
        for (int i = 0; i < gridData.length; i++) {
            if (gridData[i] == 100) {
                gridData[i] = 0;
            }
        }

        for (Map<String, Object> obs : obstacles) {
            String shape = String.valueOf(obs.getOrDefault("shape", "rectangle"));
            double x = parseDouble(obs.get("x"), 0.0);
            double y = parseDouble(obs.get("y"), 0.0);

            switch (shape) {
                case "rectangle" -> {
                    double w = parseDouble(obs.get("width"), 1.0);
                    double h = parseDouble(obs.get("height"), 1.0);
                    markRectangle(x, y, w, h, 100);
                }
                case "circle" -> {
                    double r = parseDouble(obs.get("radius"), 0.5);
                    markCircle(x, y, r, 100);
                }
                case "polygon" -> {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> points = (List<Map<String, Object>>) obs.get("points");
                    if (points != null && points.size() >= 3) {
                        markPolygon(points, 100);
                    }
                }
            }
        }
    }

    private void markRectangle(double cx, double cy, double w, double h, int value) {
        int x0 = worldToGridX(cx - w / 2);
        int x1 = worldToGridX(cx + w / 2);
        int y0 = worldToGridY(cy - h / 2);
        int y1 = worldToGridY(cy + h / 2);
        for (int gx = x0; gx <= x1; gx++) {
            for (int gy = y0; gy <= y1; gy++) {
                setGridValue(gx, gy, value);
            }
        }
    }

    private void markCircle(double cx, double cy, double r, int value) {
        int x0 = worldToGridX(cx - r);
        int x1 = worldToGridX(cx + r);
        int y0 = worldToGridY(cy - r);
        int y1 = worldToGridY(cy + r);
        double r2 = r * r;
        for (int gx = x0; gx <= x1; gx++) {
            for (int gy = y0; gy <= y1; gy++) {
                double wx = gridToWorldX(gx);
                double wy = gridToWorldY(gy);
                double dx = wx - cx;
                double dy = wy - cy;
                if (dx * dx + dy * dy <= r2) {
                    setGridValue(gx, gy, value);
                }
            }
        }
    }

    private void markPolygon(List<Map<String, Object>> points, int value) {
        // 简单包围盒法，精确的多边形填充较复杂
        double minX = Double.MAX_VALUE, minY = Double.MAX_VALUE;
        double maxX = -Double.MAX_VALUE, maxY = -Double.MAX_VALUE;
        for (Map<String, Object> p : points) {
            double px = parseDouble(p.get("x"), 0.0);
            double py = parseDouble(p.get("y"), 0.0);
            minX = Math.min(minX, px);
            minY = Math.min(minY, py);
            maxX = Math.max(maxX, px);
            maxY = Math.max(maxY, py);
        }
        int x0 = worldToGridX(minX);
        int x1 = worldToGridX(maxX);
        int y0 = worldToGridY(minY);
        int y1 = worldToGridY(maxY);
        for (int gx = x0; gx <= x1; gx++) {
            for (int gy = y0; gy <= y1; gy++) {
                double wx = gridToWorldX(gx);
                double wy = gridToWorldY(gy);
                if (pointInPolygon(wx, wy, points)) {
                    setGridValue(gx, gy, value);
                }
            }
        }
    }

    private boolean pointInPolygon(double x, double y, List<Map<String, Object>> points) {
        boolean inside = false;
        int n = points.size();
        for (int i = 0, j = n - 1; i < n; j = i++) {
            double xi = parseDouble(points.get(i).get("x"), 0.0);
            double yi = parseDouble(points.get(i).get("y"), 0.0);
            double xj = parseDouble(points.get(j).get("x"), 0.0);
            double yj = parseDouble(points.get(j).get("y"), 0.0);
            if (((yi > y) != (yj > y)) && (x < (xj - xi) * (y - yi) / (yj - yi) + xi)) {
                inside = !inside;
            }
        }
        return inside;
    }

    // ==================== 坐标转换 ====================

    private int worldToGridX(double wx) {
        return (int) Math.floor((wx - originX) / resolution);
    }

    private int worldToGridY(double wy) {
        return (int) Math.floor((wy - originY) / resolution);
    }

    private double gridToWorldX(int gx) {
        return originX + (gx + 0.5) * resolution;
    }

    private double gridToWorldY(int gy) {
        return originY + (gy + 0.5) * resolution;
    }

    private boolean isValidGrid(int gx, int gy) {
        return gx >= 0 && gx < width && gy >= 0 && gy < height;
    }

    private int getGridValue(int gx, int gy) {
        if (!isValidGrid(gx, gy)) return 100; // 地图外视为障碍
        return gridData[gy * width + gx];
    }

    private void setGridValue(int gx, int gy, int value) {
        if (isValidGrid(gx, gy)) {
            gridData[gy * width + gx] = value;
        }
    }

    // ==================== A* 路径规划 ====================

    @Override
    public List<Map<String, Object>> planPath(double startX, double startY, double goalX, double goalY) {
        int sx = worldToGridX(startX);
        int sy = worldToGridY(startY);
        int gx = worldToGridX(goalX);
        int gy = worldToGridY(goalY);

        // 检查起点/终点有效性
        if (!isValidGrid(sx, sy) || !isValidGrid(gx, gy)) {
            log.warn("起点或终点在地图外");
            return List.of();
        }
        if (getGridValue(sx, sy) >= 50 || getGridValue(gx, gy) >= 50) {
            log.warn("起点或终点在障碍物上");
            return List.of();
        }

        List<Map<String, Object>> path = aStar(sx, sy, gx, gy);

        // 路径平滑（简单角点削减）
        if (path.size() > 2) {
            path = simplifyPath(path);
        }

        return path;
    }

    private List<Map<String, Object>> aStar(int sx, int sy, int gx, int gy) {
        // 8 方向
        int[] dx = {-1, -1, -1, 0, 0, 1, 1, 1};
        int[] dy = {-1, 0, 1, -1, 1, -1, 0, 1};
        double[] dc = {1.414, 1, 1.414, 1, 1, 1.414, 1, 1.414};

        int size = width * height;
        double[] gScore = new double[size];
        double[] fScore = new double[size];
        int[] cameFrom = new int[size];
        boolean[] closed = new boolean[size];
        Arrays.fill(gScore, Double.MAX_VALUE);
        Arrays.fill(fScore, Double.MAX_VALUE);
        Arrays.fill(cameFrom, -1);

        int startIdx = sy * width + sx;
        int goalIdx = gy * width + gx;
        gScore[startIdx] = 0;
        fScore[startIdx] = heuristic(sx, sy, gx, gy);

        PriorityQueue<int[]> open = new PriorityQueue<>(Comparator.comparingDouble(a -> fScore[a[0] * width + a[1]]));
        open.offer(new int[]{sx, sy});

        while (!open.isEmpty()) {
            int[] current = open.poll();
            int cx = current[0];
            int cy = current[1];
            int cIdx = cy * width + cx;

            if (cIdx == goalIdx) {
                return reconstructPath(cameFrom, cx, cy, sx, sy);
            }

            if (closed[cIdx]) continue;
            closed[cIdx] = true;

            for (int i = 0; i < 8; i++) {
                int nx = cx + dx[i];
                int ny = cy + dy[i];
                if (!isValidGrid(nx, ny)) continue;

                int nIdx = ny * width + nx;
                if (closed[nIdx]) continue;

                // 障碍判断（>=50 视为不可通行）
                if (getGridValue(nx, ny) >= 50) continue;

                double tentativeG = gScore[cIdx] + dc[i];
                if (tentativeG < gScore[nIdx]) {
                    cameFrom[nIdx] = cIdx;
                    gScore[nIdx] = tentativeG;
                    fScore[nIdx] = tentativeG + heuristic(nx, ny, gx, gy);
                    open.offer(new int[]{nx, ny});
                }
            }
        }

        log.warn("A* 未找到路径");
        return List.of();
    }

    private double heuristic(int x1, int y1, int x2, int y2) {
        // 欧氏距离
        double dx = x1 - x2;
        double dy = y1 - y2;
        return Math.sqrt(dx * dx + dy * dy);
    }

    private List<Map<String, Object>> reconstructPath(int[] cameFrom, int gx, int gy, int sx, int sy) {
        List<Map<String, Object>> path = new ArrayList<>();
        int cx = gx;
        int cy = gy;

        while (true) {
            path.add(Map.of("x", gridToWorldX(cx), "y", gridToWorldY(cy)));
            int idx = cy * width + cx;
            if (idx == sy * width + sx) break;
            int prev = cameFrom[idx];
            if (prev < 0) break;
            cx = prev % width;
            cy = prev / width;
        }

        Collections.reverse(path);
        return path;
    }

    /**
     * 简单路径平滑：移除共线中间点
     */
    private List<Map<String, Object>> simplifyPath(List<Map<String, Object>> path) {
        if (path.size() < 3) return path;
        List<Map<String, Object>> simplified = new ArrayList<>();
        simplified.add(path.get(0));

        for (int i = 1; i < path.size() - 1; i++) {
            Map<String, Object> prev = path.get(i - 1);
            Map<String, Object> curr = path.get(i);
            Map<String, Object> next = path.get(i + 1);

            double x1 = (Double) prev.get("x");
            double y1 = (Double) prev.get("y");
            double x2 = (Double) curr.get("x");
            double y2 = (Double) curr.get("y");
            double x3 = (Double) next.get("x");
            double y3 = (Double) next.get("y");

            // 检查是否共线（叉积接近 0）
            double cross = (x2 - x1) * (y3 - y2) - (y2 - y1) * (x3 - x2);
            if (Math.abs(cross) > 1e-6) {
                simplified.add(curr);
            }
        }
        simplified.add(path.get(path.size() - 1));
        return simplified;
    }

    // ==================== 工具方法 ====================

    @SuppressWarnings("unchecked")
    private Map<String, Object> castToMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : null;
    }

    private double parseDouble(Object value, double defaultValue) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private int parseInt(Object value, int defaultValue) {
        if (value instanceof Number) return ((Number) value).intValue();
        if (value != null) {
            try {
                return Integer.parseInt(String.valueOf(value));
            } catch (NumberFormatException ignored) {
            }
        }
        return defaultValue;
    }

    private int[] parseIntArray(Object value) {
        if (value instanceof int[]) return (int[]) value;
        if (value instanceof List) {
            @SuppressWarnings("unchecked")
            List<Object> list = (List<Object>) value;
            int[] arr = new int[list.size()];
            for (int i = 0; i < list.size(); i++) {
                arr[i] = parseInt(list.get(i), -1);
            }
            return arr;
        }
        return new int[0];
    }
}
