package com.robot.scheduler.controller;

import com.robot.scheduler.common.Result;
import com.robot.scheduler.dto.ForwardKinematicsResult;
import com.robot.scheduler.dto.InverseKinematicsResult;
import com.robot.scheduler.dto.MotionTargetRequest;
import com.robot.scheduler.service.MotionService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * 视觉-运动学算法通信控制器
 * <p>
 * 提供手动发送目标物体、查询连接状态与计算结果的 REST 接口。
 */
@RestController
@RequestMapping("/api/v1/motion")
public class MotionController {

    @Autowired
    private MotionService motionService;

    /**
     * 发送目标物体给运动学算法端
     * POST /api/v1/motion/target
     */
    @PostMapping("/target")
    public Result<Map<String, Object>> sendTarget(@RequestBody MotionTargetRequest request) {
        boolean sent = motionService.sendTargetObject(
                request.getObjName(), request.getX(), request.getY(), request.getZ());

        if (sent) {
            Map<String, Object> data = new HashMap<>();
            data.put("sent", true);
            data.put("objName", request.getObjName());
            data.put("x", request.getX());
            data.put("y", request.getY());
            data.put("z", request.getZ());
            return Result.success(data);
        } else {
            return Result.error("发送失败，请检查运动学算法端连接状态");
        }
    }

    /**
     * 查询 WebSocket 连接状态
     * GET /api/v1/motion/status
     */
    @GetMapping("/status")
    public Result<Map<String, Object>> getStatus() {
        return Result.success(motionService.getConnectionStatus());
    }

    /**
     * 查询最近一次正/逆运动学结果
     * GET /api/v1/motion/result
     */
    @GetMapping("/result")
    public Result<Map<String, Object>> getResult() {
        ForwardKinematicsResult fk = motionService.getLastForwardKinematics();
        InverseKinematicsResult ik = motionService.getLastInverseKinematics();

        Map<String, Object> data = new HashMap<>();
        data.put("forwardKinematics", fk);
        data.put("inverseKinematics", ik);
        return Result.success(data);
    }
}
