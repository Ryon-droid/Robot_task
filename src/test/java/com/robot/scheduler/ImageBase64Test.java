package com.robot.scheduler;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestTemplate;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;
import java.util.Base64;

public class ImageBase64Test {

    /**
     * 前端接收地址，请根据实际情况修改。
     * 当前配置指向前端 IP: 172.16.24.131
     * 如端口或路径不同，请自行调整。
     */
    private static final String FRONTEND_URL = "http://172.16.24.131:8080/api/v1/scheduler/slam/map";

    @Test
    public void testConvertPngToBase64Json() throws Exception {
        // 1. 读取图片文件
        File imageFile = new File("D:/Robot_task/slam/final.png");
        byte[] imageBytes = Files.readAllBytes(imageFile.toPath());

        // 2. 获取图片实际宽高
        BufferedImage bufferedImage = ImageIO.read(imageFile);
        int width = bufferedImage.getWidth();
        int height = bufferedImage.getHeight();

        // 3. 转为 Base64
        String base64Image = Base64.getEncoder().encodeToString(imageBytes);

        // 4. 构建 JSON
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode root = mapper.createObjectNode();
        root.put("width", width);
        root.put("height", height);
        root.put("resolution", 0.05);

        ObjectNode origin = mapper.createObjectNode();
        origin.put("x", 0);
        origin.put("y", 0);
        root.set("origin", origin);

        root.put("image", base64Image);

        // 5. 输出 JSON
        String jsonOutput = mapper.writerWithDefaultPrettyPrinter().writeValueAsString(root);
        System.out.println("生成的 JSON：");
        System.out.println(jsonOutput);

        // 6. 发给前端（HTTP POST）
        sendToFrontend(root, mapper);
    }

    private void sendToFrontend(ObjectNode payload, ObjectMapper mapper) throws Exception {
        RestTemplate restTemplate = new RestTemplate();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        HttpEntity<String> request = new HttpEntity<>(mapper.writeValueAsString(payload), headers);

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(FRONTEND_URL, request, String.class);
            System.out.println("发送成功，HTTP 状态码：" + response.getStatusCodeValue());
            System.out.println("响应体：" + response.getBody());
        } catch (Exception e) {
            System.err.println("发送失败，请检查 FRONTEND_URL 是否正确或目标服务是否已启动：" + FRONTEND_URL);
            e.printStackTrace();
        }
    }
}
