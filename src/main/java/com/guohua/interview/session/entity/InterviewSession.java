package com.guohua.interview.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试会话实体
 */
@Data
@TableName("interview_session")
public class InterviewSession {

    public static final String STATUS_ONGOING = "ONGOING";
    public static final String STATUS_FINISHED = "FINISHED";

    /** 每场面试的题目总数 */
    public static final int TOTAL_QUESTIONS = 5;

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long userId;

    /** 面试方向：JAVA / FRONTEND / AI */
    private String direction;

    private String status;

    /** 已问的问题数（AI 每次出题 +1） */
    private Integer questionCount;

    private LocalDateTime createdAt;

    private LocalDateTime finishedAt;
}
