package com.robot.scheduler.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.robot.scheduler.dto.TaskRankingDTO;
import com.robot.scheduler.entity.Task;
import org.apache.ibatis.annotations.Result;
import org.apache.ibatis.annotations.Results;
import org.apache.ibatis.annotations.Select;

import java.util.List;

public interface TaskMapper extends BaseMapper<Task> {

    @Select("SELECT command_type, robot_id, priority, dynamic_priority_score FROM task ORDER BY dynamic_priority_score ASC, priority ASC, create_time ASC")
    @Results({
            @Result(property = "commandType", column = "command_type"),
            @Result(property = "robotId", column = "robot_id"),
            @Result(property = "urgency", column = "dynamic_priority_score")
    })
    List<TaskRankingDTO> selectTaskRankingList();
}
