package com.robot.scheduler.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.robot.scheduler.entity.MapInfo;
import com.robot.scheduler.entity.MapLive;
import com.robot.scheduler.mapper.MapInfoMapper;
import com.robot.scheduler.mapper.MapLiveMapper;
import com.robot.scheduler.service.SLAMService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;
import org.yaml.snakeyaml.Yaml;

import javax.annotation.PostConstruct;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.*;

@Slf4j
@Service
public class SLAMServiceImpl implements SLAMService {

    @Autowired
    private MapInfoMapper mapInfoMapper;

    @Autowired
    private MapLiveMapper mapLiveMapper;

    @Autowired
    private ObjectMapper objectMapper;

    // ==================== 当前激活地图（内存缓存） ====================

    private String currentMapId;

    /** 地图分辨率（米/像素） */
    private double resolution = 0.05;

    /** 地图宽度（像素） */
    private int width = 0;

    /** 地图高度（像素） */
    private int height = 0;

    /** 地图原点（世界坐标） */
    private double originX = 0.0;
    private double originY = 0.0;

    /** 原始栅格数据（从 PGM 解析，不含动态障碍物） */
    private int[] baseGridData = new int[0];

    /** 当前栅格数据（含动态障碍物） */
    private int[] gridData = new int[0];

    /** 是否正在建图 */
    private boolean isMapping = false;

    // ==================== 障碍物 / 空气墙 ====================

    private final List<Map<String, Object>> obstacles = new ArrayList<>();

    // ==================== 初始化 ====================

    @PostConstruct
    public void init() {
        // 1. 尝试从数据库加载激活地图
        MapInfo activeMap = mapInfoMapper.selectOne(
                new QueryWrapper<MapInfo>().eq("is_active", 1).last("LIMIT 1")
        );
        if (activeMap != null) {
            loadMapToMemory(activeMap);
            log.info("从数据库加载激活地图: {} ({})", activeMap.getMapName(), activeMap.getMapId());
            return;
        }

        // 2. 没有激活地图，尝试导入 slam/guli 作为默认地图
        try {
            java.nio.file.Path pgmPath = Paths.get("slam/guli.pgm");
            java.nio.file.Path yamlPath = Paths.get("slam/guli.yaml");
            if (Files.exists(pgmPath) && Files.exists(yamlPath)) {
                byte[] pgmBytes = Files.readAllBytes(pgmPath);
                String yamlStr = Files.readString(yamlPath, StandardCharsets.UTF_8);

                MapInfo mapInfo = buildMapInfo("默认地图", pgmBytes, yamlStr);
                mapInfo.setMapId(UUID.randomUUID().toString().replace("-", ""));
                mapInfo.setIsActive(1);
                mapInfoMapper.insert(mapInfo);

                loadMapToMemory(mapInfo);
                log.info("默认地图导入成功: {} ({})", mapInfo.getMapName(), mapInfo.getMapId());
            } else {
                // 3. 连默认文件都没有，创建空地图
                resetMapInternal(20.0, 20.0, 0.05, -10.0, -10.0);
                log.info("未找到默认地图，初始化空地图");
            }
        } catch (Exception e) {
            log.error("加载默认地图失败", e);
            resetMapInternal(20.0, 20.0, 0.05, -10.0, -10.0);
        }

        // 尝试从数据库恢复实时地图（覆盖静态地图的内存状态）
        try {
            MapLive live = mapLiveMapper.selectById("current");
            if (live != null && live.getGridData() != null && !live.getGridData().isEmpty()) {
                this.currentMapId = live.getMapId();
                this.resolution = live.getResolution() != null ? live.getResolution() : this.resolution;
                this.originX = live.getOriginX() != null ? live.getOriginX() : this.originX;
                this.originY = live.getOriginY() != null ? live.getOriginY() : this.originY;
                this.width = live.getWidth() != null ? live.getWidth() : this.width;
                this.height = live.getHeight() != null ? live.getHeight() : this.height;
                this.gridData = objectMapper.readValue(live.getGridData(), int[].class);
                this.baseGridData = new int[this.gridData.length];
                System.arraycopy(this.gridData, 0, this.baseGridData, 0, this.gridData.length);
                if (live.getObstacles() != null && !live.getObstacles().isEmpty()) {
                    List<Map<String, Object>> savedObstacles = objectMapper.readValue(
                            live.getObstacles(), new TypeReference<List<Map<String, Object>>>() {});
                    this.obstacles.clear();
                    this.obstacles.addAll(savedObstacles);
                }
                log.info("从数据库恢复实时地图: {}x{}，障碍物 {} 个", this.width, this.height, this.obstacles.size());
            }
        } catch (Exception e) {
            log.warn("恢复实时地图失败", e);
        }
    }

    // ==================== 地图管理 ====================

    @Override
    public Map<String, Object> getMapData() {
        Map<String, Object> result = new HashMap<>();
        result.put("mapId", currentMapId);
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
            this.baseGridData = new int[this.gridData.length];
            System.arraycopy(this.gridData, 0, this.baseGridData, 0, this.gridData.length);
        }

        persistMapLive();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "更新地图数据成功");
        return result;
    }

    @Override
    public Map<String, Object> resetMap() {
        obstacles.clear();
        if (currentMapId != null) {
            MapInfo mapInfo = mapInfoMapper.selectById(currentMapId);
            if (mapInfo != null) {
                loadMapToMemory(mapInfo);
                Map<String, Object> result = new HashMap<>();
                result.put("status", "success");
                result.put("message", "地图已重置为初始状态");
                return result;
            }
        }
        resetMapInternal(20.0, 20.0, 0.05, -10.0, -10.0);
        obstacles.clear();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("message", "重置地图成功（空地图）");
        return result;
    }

    private void resetMapInternal(double mapWidthMeters, double mapHeightMeters,
                                   double res, double ox, double oy) {
        this.resolution = res;
        this.width = (int) Math.ceil(mapWidthMeters / res);
        this.height = (int) Math.ceil(mapHeightMeters / res);
        this.originX = ox;
        this.originY = oy;
        this.baseGridData = new int[this.width * this.height];
        this.gridData = new int[this.width * this.height];
        Arrays.fill(this.baseGridData, 0);
        Arrays.fill(this.gridData, 0);
        this.isMapping = false;
        this.currentMapId = null;
    }

    @Override
    public Map<String, Object> getMapStatus() {
        Map<String, Object> result = new HashMap<>();
        result.put("mapId", currentMapId);
        result.put("isMapping", isMapping);
        result.put("width", width);
        result.put("height", height);
        result.put("resolution", resolution);
        result.put("origin", Map.of("x", originX, "y", originY));
        result.put("obstacleCount", obstacles.size());
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> uploadMap(String mapName, MultipartFile pgmFile, MultipartFile yamlFile) {
        try {
            byte[] pgmBytes = pgmFile.getBytes();
            String yamlStr = new String(yamlFile.getBytes(), StandardCharsets.UTF_8);

            MapInfo mapInfo = buildMapInfo(mapName, pgmBytes, yamlStr);
            mapInfo.setMapId(UUID.randomUUID().toString().replace("-", ""));
            mapInfo.setIsActive(0);
            mapInfoMapper.insert(mapInfo);

            Map<String, Object> result = new HashMap<>();
            result.put("status", "success");
            result.put("mapId", mapInfo.getMapId());
            result.put("message", "地图上传成功");
            return result;
        } catch (Exception e) {
            log.error("上传地图失败", e);
            throw new RuntimeException("上传地图失败: " + e.getMessage());
        }
    }

    @Override
    @Transactional
    public Map<String, Object> switchMap(String mapId) {
        MapInfo target = mapInfoMapper.selectById(mapId);
        if (target == null) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "地图不存在");
            return result;
        }

        if (currentMapId != null) {
            MapInfo current = new MapInfo();
            current.setMapId(currentMapId);
            current.setIsActive(0);
            mapInfoMapper.updateById(current);
        }

        MapInfo update = new MapInfo();
        update.setMapId(mapId);
        update.setIsActive(1);
        mapInfoMapper.updateById(update);

        loadMapToMemory(target);
        obstacles.clear();

        Map<String, Object> result = new HashMap<>();
        result.put("status", "success");
        result.put("mapId", mapId);
        result.put("message", "地图切换成功");
        return result;
    }

    @Override
    public List<Map<String, Object>> listMaps() {
        List<MapInfo> maps = mapInfoMapper.selectList(
                new QueryWrapper<MapInfo>().orderByDesc("create_time")
        );
        List<Map<String, Object>> result = new ArrayList<>();
        for (MapInfo m : maps) {
            Map<String, Object> item = new HashMap<>();
            item.put("mapId", m.getMapId());
            item.put("mapName", m.getMapName());
            item.put("resolution", m.getResolution());
            item.put("width", m.getWidth());
            item.put("height", m.getHeight());
            item.put("originX", m.getOriginX());
            item.put("originY", m.getOriginY());
            item.put("originYaw", m.getOriginYaw());
            item.put("isActive", m.getIsActive());
            item.put("createTime", m.getCreateTime());
            result.add(item);
        }
        return result;
    }

    @Override
    @Transactional
    public Map<String, Object> deleteMap(String mapId) {
        if (mapId.equals(currentMapId)) {
            Map<String, Object> result = new HashMap<>();
            result.put("status", "error");
            result.put("message", "不能删除当前激活的地图");
            return result;
        }
        int rows = mapInfoMapper.deleteById(mapId);
        Map<String, Object> result = new HashMap<>();
        result.put("status", rows > 0 ? "success" : "error");
        result.put("message", rows > 0 ? "删除成功" : "地图不存在");
        return result;
    }

    // ==================== 内部加载方法 ====================

    private synchronized void loadMapToMemory(MapInfo mapInfo) {
        this.currentMapId = mapInfo.getMapId();
        this.resolution = mapInfo.getResolution() != null ? mapInfo.getResolution() : 0.05;
        this.originX = mapInfo.getOriginX() != null ? mapInfo.getOriginX() : 0.0;
        this.originY = mapInfo.getOriginY() != null ? mapInfo.getOriginY() : 0.0;
        this.width = mapInfo.getWidth() != null ? mapInfo.getWidth() : 0;
        this.height = mapInfo.getHeight() != null ? mapInfo.getHeight() : 0;

        if (mapInfo.getPgmData() != null && mapInfo.getPgmData().length > 0
                && this.width > 0 && this.height > 0) {
            PgmHeader header = parsePgmHeader(mapInfo.getPgmData());
            int negate = mapInfo.getNegate() != null ? mapInfo.getNegate() : 0;
            double occThresh = mapInfo.getOccupiedThresh() != null ? mapInfo.getOccupiedThresh() : 0.65;
            double freeThresh = mapInfo.getFreeThresh() != null ? mapInfo.getFreeThresh() : 0.25;
            this.baseGridData = convertPixelsToGrid(header.pixels, header.width, header.height,
                    negate, occThresh, freeThresh);
            this.gridData = new int[this.baseGridData.length];
            System.arraycopy(this.baseGridData, 0, this.gridData, 0, this.baseGridData.length);
            applyObstaclesToGrid();
        } else {
            int size = this.width * this.height;
            if (size <= 0) size = 400 * 400; // fallback
            this.baseGridData = new int[size];
            this.gridData = new int[size];
        }
        this.isMapping = false;
    }

    private MapInfo buildMapInfo(String mapName, byte[] pgmBytes, String yamlStr) {
        YamlMeta meta = parseYaml(yamlStr);
        PgmHeader header = parsePgmHeader(pgmBytes);

        MapInfo mapInfo = new MapInfo();
        mapInfo.setMapName(mapName);
        mapInfo.setPgmData(pgmBytes);
        mapInfo.setYamlData(yamlStr);
        mapInfo.setResolution(meta.resolution);
        mapInfo.setOriginX(meta.originX);
        mapInfo.setOriginY(meta.originY);
        mapInfo.setOriginYaw(meta.originYaw);
        mapInfo.setWidth(header.width);
        mapInfo.setHeight(header.height);
        mapInfo.setNegate(meta.negate);
        mapInfo.setOccupiedThresh(meta.occupiedThresh);
        mapInfo.setFreeThresh(meta.freeThresh);
        return mapInfo;
    }

    // ==================== PGM / YAML 解析 ====================

    private static class PgmHeader {
        int width, height, maxVal;
        byte[] pixels;
        PgmHeader(int w, int h, int m, byte[] p) {
            this.width = w; this.height = h; this.maxVal = m; this.pixels = p;
        }
    }

    private static class YamlMeta {
        String image;
        double resolution;
        double originX, originY, originYaw;
        int negate;
        double occupiedThresh;
        double freeThresh;
    }

    private PgmHeader parsePgmHeader(byte[] data) {
        int i = 0;
        int len = data.length;

        while (i < len && isWhitespace(data[i])) i++;

        StringBuilder sb = new StringBuilder();
        while (i < len && !isWhitespace(data[i])) {
            sb.append((char) data[i++]);
        }
        String magic = sb.toString();
        if (!"P5".equals(magic)) {
            throw new IllegalArgumentException("不支持的 PGM 格式: " + magic + "，仅支持 P5");
        }

        while (i < len) {
            while (i < len && isWhitespace(data[i])) i++;
            if (i < len && data[i] == '#') {
                while (i < len && data[i] != '\n' && data[i] != '\r') i++;
            } else {
                break;
            }
        }

        int width = readNextInt(data, len, i);
        i = skipAfterInt(data, len, i);

        int height = readNextInt(data, len, i);
        i = skipAfterInt(data, len, i);

        int maxVal = readNextInt(data, len, i);
        i = skipAfterInt(data, len, i);

        // skip exactly one whitespace after maxVal
        if (i < len && isWhitespace(data[i])) i++;

        int pixelCount = width * height;
        if (data.length - i < pixelCount) {
            throw new IllegalArgumentException(
                    "PGM 数据不完整，期望 " + pixelCount + " 字节，实际 " + (data.length - i));
        }

        byte[] pixels = new byte[pixelCount];
        System.arraycopy(data, i, pixels, 0, pixelCount);
        return new PgmHeader(width, height, maxVal, pixels);
    }

    private int readNextInt(byte[] data, int len, int start) {
        int i = start;
        while (i < len && isWhitespace(data[i])) i++;
        StringBuilder sb = new StringBuilder();
        while (i < len && !isWhitespace(data[i])) {
            sb.append((char) data[i++]);
        }
        return Integer.parseInt(sb.toString().trim());
    }

    private int skipAfterInt(byte[] data, int len, int start) {
        int i = start;
        while (i < len && isWhitespace(data[i])) i++;
        while (i < len && !isWhitespace(data[i])) i++;
        return i;
    }

    private boolean isWhitespace(byte b) {
        return b == ' ' || b == '\t' || b == '\n' || b == '\r';
    }

    private int[] convertPixelsToGrid(byte[] pixels, int width, int height, int negate,
                                       double occupiedThresh, double freeThresh) {
        int[] grid = new int[pixels.length];
        for (int row = 0; row < height; row++) {
            for (int col = 0; col < width; col++) {
                int srcIdx = row * width + col;
                // PGM 图像 row=0 是顶部，地图坐标 y=0 是底部，需要翻转
                int dstIdx = (height - 1 - row) * width + col;
                int pixel = pixels[srcIdx] & 0xFF;
                double p;
                if (negate == 0) {
                    p = (255.0 - pixel) / 255.0;
                } else {
                    p = pixel / 255.0;
                }
                if (p > occupiedThresh) {
                    grid[dstIdx] = 100; // occupied
                } else if (p < freeThresh) {
                    grid[dstIdx] = 0;   // free
                } else {
                    grid[dstIdx] = -1;  // unknown
                }
            }
        }
        return grid;
    }

    private YamlMeta parseYaml(String yamlContent) {
        Yaml yaml = new Yaml();
        Map<String, Object> map = yaml.load(yamlContent);
        YamlMeta meta = new YamlMeta();
        meta.image = (String) map.get("image");
        meta.resolution = ((Number) map.get("resolution")).doubleValue();
        @SuppressWarnings("unchecked")
        List<Number> origin = (List<Number>) map.get("origin");
        meta.originX = origin.get(0).doubleValue();
        meta.originY = origin.get(1).doubleValue();
        meta.originYaw = origin.get(2).doubleValue();
        meta.negate = map.containsKey("negate") ? ((Number) map.get("negate")).intValue() : 0;
        meta.occupiedThresh = ((Number) map.get("occupied_thresh")).doubleValue();
        meta.freeThresh = ((Number) map.get("free_thresh")).doubleValue();
        return meta;
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
        if (!obstacleData.containsKey("type")) {
            obstacleData.put("type", "obstacle");
        }
        if (!obstacleData.containsKey("shape")) {
            obstacleData.put("shape", "rectangle");
        }
        obstacles.add(obstacleData);
        applyObstaclesToGrid();
        persistMapLive();

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
            persistMapLive();
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
                persistMapLive();
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
        if (baseGridData != null && gridData != null && baseGridData.length == gridData.length) {
            System.arraycopy(baseGridData, 0, gridData, 0, baseGridData.length);
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

    /**
     * 将当前实时地图（含栅格与障碍物）持久化到数据库
     */
    private void persistMapLive() {
        try {
            if (this.gridData == null || this.gridData.length == 0) {
                return;
            }
            MapLive live = new MapLive();
            live.setLiveId("current");
            live.setMapId(this.currentMapId);
            live.setResolution(this.resolution);
            live.setWidth(this.width);
            live.setHeight(this.height);
            live.setOriginX(this.originX);
            live.setOriginY(this.originY);
            live.setOriginYaw(0.0);
            live.setGridData(objectMapper.writeValueAsString(this.gridData));
            live.setObstacles(objectMapper.writeValueAsString(this.obstacles));

            int rows = mapLiveMapper.updateById(live);
            if (rows == 0) {
                mapLiveMapper.insert(live);
            }
        } catch (Exception e) {
            log.error("持久化实时地图失败", e);
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
        if (!isValidGrid(gx, gy)) return 100;
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

        if (!isValidGrid(sx, sy) || !isValidGrid(gx, gy)) {
            log.warn("起点或终点在地图外");
            return List.of();
        }
        if (getGridValue(sx, sy) >= 50 || getGridValue(gx, gy) >= 50) {
            log.warn("起点或终点在障碍物上");
            return List.of();
        }

        List<Map<String, Object>> path = aStar(sx, sy, gx, gy);
        if (path.size() > 2) {
            path = simplifyPath(path);
        }
        return path;
    }

    private List<Map<String, Object>> aStar(int sx, int sy, int gx, int gy) {
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
