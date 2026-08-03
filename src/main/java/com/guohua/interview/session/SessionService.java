package com.guohua.interview.session;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.guohua.interview.ai.DeepSeekClient;
import com.guohua.interview.common.BizException;
import com.guohua.interview.rag.RagClient;
import com.guohua.interview.report.ReportService;
import com.guohua.interview.session.entity.InterviewMessage;
import com.guohua.interview.session.entity.InterviewSession;
import com.guohua.interview.session.mapper.InterviewMessageMapper;
import com.guohua.interview.session.mapper.InterviewSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 面试会话核心服务：面试编排（出题 → 回答 → 点评/追问）与消息落库。
 * <p>
 * 设计要点：
 * 1. 对话上下文复用 RAG 项目的"保留最近 N 条消息"思路，控制 token 成本；
 * 2. 每轮 AI 出题前通过 RagClient 检索知识库知识点注入 prompt，RAG 不可用时自动降级纯 LLM；
 * 3. 面试轮次由 questionCount 服务端控制，AI 只负责内容。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SessionService {

    /** 传给大模型的历史消息上限（最近 10 条 + 当前，约 5 轮对话） */
    private static final int MAX_HISTORY = 10;

    private final InterviewSessionMapper sessionMapper;
    private final InterviewMessageMapper messageMapper;
    private final DeepSeekClient deepSeekClient;
    private final RagClient ragClient;
    private final ReportService reportService;

    /** 异步任务执行器（虚拟线程）：报告生成等耗时操作不阻塞请求线程 */
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();

    private static final Map<String, String> DIRECTION_NAMES = Map.of(
            "JAVA", "Java 后端",
            "FRONTEND", "前端",
            "AI", "AI 应用");

    // ==================== 创建面试 + 第一题 ====================

    @Transactional
    public Map<String, Object> create(Long userId, String direction) {
        if (!DIRECTION_NAMES.containsKey(direction)) {
            throw BizException.badRequest("不支持的面试方向: " + direction);
        }

        InterviewSession session = new InterviewSession();
        session.setUserId(userId);
        session.setDirection(direction);
        session.setStatus(InterviewSession.STATUS_ONGOING);
        session.setQuestionCount(0);
        sessionMapper.insert(session);

        // 第一题：注入方向相关知识点（RAG 不可用时自动降级）
        String ragKnowledge = ragClient.retrieve(DIRECTION_NAMES.get(direction) + " 面试常见题");
        String userContent = "请开始面试，提出第一道面试题。" + buildRagSuffix(ragKnowledge);

        String aiReply = askAi(session, buildMessages(session.getId(),
                List.of(new DeepSeekClient.ChatMessage("user", userContent))));

        saveMessage(session.getId(), InterviewMessage.ROLE_AI, aiReply);
        session.setQuestionCount(1);
        sessionMapper.updateById(session);

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("sessionId", session.getId());
        data.put("question", aiReply);
        data.put("questionCount", session.getQuestionCount());
        data.put("totalQuestions", InterviewSession.TOTAL_QUESTIONS);
        return data;
    }

    // ==================== 回答 + 点评/下一题 ====================

    @Transactional
    public Map<String, Object> answer(Long userId, Long sessionId, String content) {
        InterviewSession session = getOwnedSession(userId, sessionId);
        if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw BizException.badRequest("面试已结束，请查看报告");
        }

        saveMessage(sessionId, InterviewMessage.ROLE_USER, content);

        int nextCount = session.getQuestionCount() + 1;
        boolean isLast = nextCount >= InterviewSession.TOTAL_QUESTIONS;

        // 最后一题时提示 AI 收尾总结
        String userContent = isLast
                ? content + "\n\n（这是本场面试的最后一个问题，请点评后给出整场面试的简短总结与建议）"
                : content;

        // 回答轮也检索相关知识辅助点评（同样可降级）
        String ragKnowledge = ragClient.retrieve(content);
        String aiReply = askAi(session, buildMessages(session.getId(),
                List.of(new DeepSeekClient.ChatMessage("user", userContent + buildRagSuffix(ragKnowledge)))));

        saveMessage(sessionId, InterviewMessage.ROLE_AI, aiReply);
        session.setQuestionCount(nextCount);
        if (isLast) {
            session.setStatus(InterviewSession.STATUS_FINISHED);
            session.setFinishedAt(LocalDateTime.now());
        }
        sessionMapper.updateById(session);

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("sessionId", sessionId);
        data.put("reply", aiReply);
        data.put("finished", isLast);
        data.put("questionCount", nextCount);
        data.put("totalQuestions", InterviewSession.TOTAL_QUESTIONS);
        return data;
    }

    // ==================== 回答（SSE 流式版） ====================

    /**
     * 流式回答：AI 增量内容通过 SSE 推送（打字机效果），全部完成后落库并推进轮次。
     * 由调用方在独立线程（虚拟线程）执行；SSE 事件：
     *   {"type":"delta","content":"..."}  增量
     *   {"type":"done","finished":bool,"questionCount":n,"sessionId":id}
     *   {"type":"error","message":"..."}
     */
    public void answerStream(Long userId, Long sessionId, String content, SseEmitter emitter) {
        StringBuilder full = new StringBuilder();
        boolean aiSaved = false;
        try {
            InterviewSession session = getOwnedSession(userId, sessionId);
            if (InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
                throw BizException.badRequest("面试已结束，请查看报告");
            }
            saveMessage(sessionId, InterviewMessage.ROLE_USER, content);

            int nextCount = session.getQuestionCount() + 1;
            boolean isLast = nextCount >= InterviewSession.TOTAL_QUESTIONS;
            String userContent = isLast
                    ? content + "\n\n（这是本场面试的最后一个问题，请点评后给出整场面试的简短总结与建议）"
                    : content;
            String ragKnowledge = ragClient.retrieve(content);
            List<DeepSeekClient.ChatMessage> messages = buildMessages(session.getId(),
                    List.of(new DeepSeekClient.ChatMessage("user", userContent + buildRagSuffix(ragKnowledge))));

            deepSeekClient.chatStream(messages, delta -> {
                if (delta.isEmpty()) {
                    return; // 跳过空增量（角色切换时的占位 chunk）
                }
                full.append(delta);
                sendEvent(emitter, Map.of("type", "delta", "content", delta));
            });

            // 流结束：落库 AI 消息 + 推进轮次
            saveMessage(sessionId, InterviewMessage.ROLE_AI, full.toString());
            aiSaved = true;
            session.setQuestionCount(nextCount);
            if (isLast) {
                session.setStatus(InterviewSession.STATUS_FINISHED);
                session.setFinishedAt(LocalDateTime.now());
                // 异步生成评估报告，不阻塞面试流收尾
                Long currentUserId = userId;
                asyncExecutor.execute(() -> {
                    try {
                        reportService.generate(currentUserId, sessionId);
                    } catch (Exception ex) {
                        log.error("报告生成失败 session={}", sessionId, ex);
                    }
                });
            }
            sessionMapper.updateById(session);

            sendEvent(emitter, Map.of(
                    "type", "finished",
                    "finished", isLast,
                    "questionCount", nextCount,
                    "totalQuestions", InterviewSession.TOTAL_QUESTIONS,
                    "sessionId", sessionId));
            emitter.complete();
        } catch (Exception e) {
            log.error("流式回答异常 session={}", sessionId, e);
            // 客户端断开/异常时，把已生成的部分内容也落库，刷新页面不丢消息
            if (!aiSaved && full.length() > 0) {
                try {
                    saveMessage(sessionId, InterviewMessage.ROLE_AI, full.toString());
                } catch (Exception ignored) {
                    log.warn("断线内容落库失败 session={}", sessionId);
                }
            }
            sendEvent(emitter, Map.of("type", "error", "message", e.getMessage()));
            emitter.complete();
        }
    }

    private void sendEvent(SseEmitter emitter, Object data) {
        try {
            emitter.send(SseEmitter.event().name("message").data(data));
        } catch (IOException e) {
            throw new BizException(500, "SSE 连接中断");
        }
    }

    // ==================== 查询 ====================

    public List<InterviewSession> listByUser(Long userId) {
        return sessionMapper.selectList(new LambdaQueryWrapper<InterviewSession>()
                .eq(InterviewSession::getUserId, userId)
                .orderByDesc(InterviewSession::getCreatedAt));
    }

    public List<InterviewMessage> messages(Long userId, Long sessionId) {
        getOwnedSession(userId, sessionId);
        return messageMapper.selectList(new LambdaQueryWrapper<InterviewMessage>()
                .eq(InterviewMessage::getSessionId, sessionId)
                .orderByAsc(InterviewMessage::getId));
    }

    // ==================== 内部方法 ====================

    /** 构建传给大模型的 messages：system + 最近历史 + 当前轮 */
    private List<DeepSeekClient.ChatMessage> buildMessages(Long sessionId, List<DeepSeekClient.ChatMessage> current) {
        List<DeepSeekClient.ChatMessage> messages = new ArrayList<>();

        InterviewSession session = sessionMapper.selectById(sessionId);
        messages.add(new DeepSeekClient.ChatMessage("system", buildSystemPrompt(session.getDirection())));

        // 历史消息（最近的，控制 token）：DB 角色 AI/USER → OpenAI 角色 assistant/user
        List<InterviewMessage> history = messageMapper.selectList(new LambdaQueryWrapper<InterviewMessage>()
                .eq(InterviewMessage::getSessionId, sessionId)
                .orderByDesc(InterviewMessage::getId)
                .last("LIMIT " + MAX_HISTORY));
        for (int i = history.size() - 1; i >= 0; i--) {
            InterviewMessage m = history.get(i);
            messages.add(new DeepSeekClient.ChatMessage(
                    InterviewMessage.ROLE_AI.equals(m.getRole()) ? "assistant" : "user",
                    m.getContent()));
        }
        messages.addAll(current);
        return messages;
    }

    /** 调用大模型；AI 失败时给出可读错误 */
    private String askAi(InterviewSession session, List<DeepSeekClient.ChatMessage> messages) {
        try {
            return deepSeekClient.chat(messages);
        } catch (BizException e) {
            log.error("会话 {} 第 {} 题 AI 调用失败: {}", session.getId(), session.getQuestionCount() + 1, e.getMessage());
            throw BizException.badRequest("AI 暂时无法回答，请稍后重试（" + e.getMessage() + "）");
        }
    }

    /** RAG 检索到的知识附加到 user 消息尾部；null 时返回空串（不干扰 prompt） */
    private String buildRagSuffix(String ragKnowledge) {
        if (ragKnowledge == null || ragKnowledge.isBlank()) {
            return "";
        }
        String truncated = ragKnowledge.length() > 500 ? ragKnowledge.substring(0, 500) : ragKnowledge;
        return "\n\n[知识库参考资料] " + truncated + "\n（请参考以上知识点组织题目/点评，不要直接复述给候选人）";
    }

    /** 校验会话归属并返回 */
    private InterviewSession getOwnedSession(Long userId, Long sessionId) {
        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw BizException.unauthorized("无权访问该会话");
        }
        return session;
    }

    private void saveMessage(Long sessionId, String role, String content) {
        InterviewMessage message = new InterviewMessage();
        message.setSessionId(sessionId);
        message.setRole(role);
        message.setContent(content);
        messageMapper.insert(message);
    }

    private String buildSystemPrompt(String direction) {
        return """
                你是一名资深%s技术面试官，正在为求职者进行模拟面试。
                面试规则：
                1. 本场共 %d 道题，逐题进行，每轮只输出一道题；
                2. 候选人作答后，先用 2-3 句话点评（指出亮点与不足），再提出下一道题；
                3. 题目从基础到进阶递进，贴近真实面试考点，兼顾概念与实战场景；
                4. 全程使用中文，点评要具体、专业、不空洞。
                当前面试方向：%s
                """.formatted(DIRECTION_NAMES.get(direction), InterviewSession.TOTAL_QUESTIONS, DIRECTION_NAMES.get(direction));
    }
}
