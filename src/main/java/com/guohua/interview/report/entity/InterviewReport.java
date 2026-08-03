package com.guohua.interview.report.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 面试报告实体
 */
@Data
@TableName("interview_report")
public class InterviewReport {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long sessionId;

    /** 总分（0-100） */
    private Integer overallScore;

    /** 逐题点评（JSON 数组：{question, score, comment}） */
    private String qaReviews;

    private String summary;

    private String suggestions;

    private LocalDateTime createdAt;
}
