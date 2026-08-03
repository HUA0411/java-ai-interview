package com.guohua.interview.session;

import com.guohua.interview.common.BizException;
import com.guohua.interview.common.Result;
import com.guohua.interview.config.JwtInterceptor;
import com.guohua.interview.report.ReportService;
import com.guohua.interview.report.entity.InterviewReport;
import com.guohua.interview.session.entity.InterviewMessage;
import com.guohua.interview.session.entity.InterviewSession;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面试会话接口
 */
@RestController
@RequestMapping("/api/session")
@RequiredArgsConstructor
@Validated
public class SessionController {

    private final SessionService sessionService;
    private final ReportService reportService;

    /** 虚拟线程执行流式任务（Java 21+ 虚拟线程，高并发下阻塞 IO 不占平台线程） */
    private final ExecutorService aiExecutor = Executors.newVirtualThreadPerTaskExecutor();

    public record CreateRequest(@NotBlank(message = "请选择面试方向") String direction) {
    }

    public record AnswerRequest(@NotBlank(message = "回答不能为空") String content) {
    }

    /** 创建面试并返回第一道题 */
    @PostMapping("/create")
    public Result<Map<String, Object>> create(@Validated @RequestBody CreateRequest req,
                                              HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        return Result.ok(sessionService.create(userId, req.direction()));
    }

    /** 提交回答，返回 AI 点评与下一题 */
    @PostMapping("/{sessionId}/answer")
    public Result<Map<String, Object>> answer(@PathVariable Long sessionId,
                                              @Validated @RequestBody AnswerRequest req,
                                              HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        return Result.ok(sessionService.answer(userId, sessionId, req.content()));
    }

    /** 流式回答：AI 内容以 SSE 增量推送（打字机效果） */
    @PostMapping("/{sessionId}/answer-stream")
    public SseEmitter answerStream(@PathVariable Long sessionId,
                                   @Validated @RequestBody AnswerRequest req,
                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        SseEmitter emitter = new SseEmitter(120_000L);
        aiExecutor.execute(() -> sessionService.answerStream(userId, sessionId, req.content(), emitter));
        return emitter;
    }

    /** 我的会话列表 */
    @GetMapping("/list")
    public Result<List<InterviewSession>> list(HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        return Result.ok(sessionService.listByUser(userId));
    }

    /** 会话评估报告（可能仍在异步生成中，前端轮询；未结束的会话返回 400） */
    @GetMapping("/{sessionId}/report")
    public Result<InterviewReport> report(@PathVariable Long sessionId,
                                          HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        InterviewReport report = reportService.getBySession(sessionId);
        if (report == null) {
            // 校验归属，避免越权探测
            sessionService.messages(userId, sessionId);
            throw new BizException(404, "报告生成中，请稍后刷新");
        }
        return Result.ok(report);
    }

    /** 会话消息（刷新页面恢复对话用） */
    @GetMapping("/{sessionId}/messages")
    public Result<List<InterviewMessage>> messages(@PathVariable Long sessionId,
                                                   HttpServletRequest request) {
        Long userId = (Long) request.getAttribute(JwtInterceptor.ATTR_USER_ID);
        return Result.ok(sessionService.messages(userId, sessionId));
    }
}
