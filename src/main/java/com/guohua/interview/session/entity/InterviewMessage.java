package com.guohua.interview.session.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 会话消息实体
 */
@Data
@TableName("interview_message")
public class InterviewMessage {

    public static final String ROLE_AI = "AI";
    public static final String ROLE_USER = "USER";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    private String role;

    private String content;

    private LocalDateTime createdAt;
}
