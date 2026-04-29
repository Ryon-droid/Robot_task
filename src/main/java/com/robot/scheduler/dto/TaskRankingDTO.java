package com.robot.scheduler.dto;

import lombok.Data;

@Data
public class TaskRankingDTO {
    private String commandType;
    private String robotId;
    private Integer priority;
    private Double urgency;
}
