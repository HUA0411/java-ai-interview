package com.guohua.interview.report;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guohua.interview.ai.DeepSeekClient;
import com.guohua.interview.common.BizException;
import com.guohua.interview.report.entity.InterviewReport;
import com.guohua.interview.report.mapper.InterviewReportMapper;
import com.guohua.interview.session.entity.InterviewMessage;
import com.guohua.interview.session.entity.InterviewSession;
import com.guohua.interview.session.mapper.InterviewMessageMapper;
import com.guohua.interview.session.mapper.InterviewSessionMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 面试报告服务：面试结束后由 AI 生成结构化评估报告。
 * <p>
 * 设计：将整场「题目 + 回答」交给大模型，要求输出固定结构 JSON
 * （总分 / 逐题点评 / 综合评语 / 改进建议），服务端解析后落库；
 * JSON 解析失败时降级为纯文本报告，保证报告一定可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ReportService {

    private final InterviewReportMapper reportMapper;
    private final InterviewSessionMapper sessionMapper;
    private final InterviewMessageMapper messageMapper;
    private final DeepSeekClient deepSeekClient;
    private final ObjectMapper mapper = new ObjectMapper();

    /** 生成报告（幂等：已存在直接返回）；由面试结束后的异步任务调用 */
    public InterviewReport generate(Long userId, Long sessionId) {
        InterviewReport existing = getBySession(sessionId);
        if (existing != null) {
            return existing;
        }

        InterviewSession session = sessionMapper.selectById(sessionId);
        if (session == null) {
            throw BizException.notFound("会话不存在");
        }
        if (!session.getUserId().equals(userId)) {
            throw BizException.unauthorized("无权访问该会话");
        }
        if (!InterviewSession.STATUS_FINISHED.equals(session.getStatus())) {
            throw BizException.badRequest("面试尚未结束，无法生成报告");
        }

        // 组装整场对话文本
        List<InterviewMessage> messages = messageMapper.selectList(new LambdaQueryWrapper<InterviewMessage>()
                .eq(InterviewMessage::getSessionId, sessionId)
                .orderByAsc(InterviewMessage::getId));
        StringBuilder transcript = new StringBuilder();
        for (InterviewMessage m : messages) {
            transcript.append(InterviewMessage.ROLE_AI.equals(m.getRole()) ? "【面试官】" : "【候选人】")
                    .append(m.getContent()).append("\n\n");
        }

        String aiReply = deepSeekClient.chat(List.of(
                new DeepSeekClient.ChatMessage("system", SYSTEM_PROMPT),
                new DeepSeekClient.ChatMessage("user",
                        "面试方向：" + session.getDirection() + "\n\n面试记录：\n" + transcript)));

        InterviewReport report = parseReport(aiReply, sessionId);
        reportMapper.insert(report);
        log.info("面试报告已生成 session={} score={}", sessionId, report.getOverallScore());
        return report;
    }

    public InterviewReport getBySession(Long sessionId) {
        return reportMapper.selectOne(new LambdaQueryWrapper<InterviewReport>()
                .eq(InterviewReport::getSessionId, sessionId));
    }

    /** 解析 AI 返回的 JSON；失败时降级为纯文本报告 */
    private InterviewReport parseReport(String aiReply, Long sessionId) {
        InterviewReport report = new InterviewReport();
        report.setSessionId(sessionId);
        report.setQaReviews("[]");
        report.setSummary("（报告生成失败，AI 返回格式异常）");
        report.setSuggestions("请重试生成报告");

        try {
            // 兼容 AI 返回 markdown 代码块包裹的情况
            String json = aiReply.replaceAll("```json\\s*|```", "").trim();
            int start = json.indexOf('{');
            int end = json.lastIndexOf('}');
            if (start >= 0 && end > start) {
                json = json.substring(start, end + 1);
            }
            JsonNode root = mapper.readTree(json);
            report.setOverallScore(root.path("overall_score").asInt(60));
            report.setQaReviews(root.path("qa_reviews").isArray()
                    ? root.path("qa_reviews").toString()
                    : "[]");
            report.setSummary(root.path("summary").asText(""));
            report.setSuggestions(root.path("suggestions").asText(""));
        } catch (Exception e) {
            log.warn("报告 JSON 解析失败，降级为纯文本 session={}", sessionId);
            report.setOverallScore(60);
            report.setQaReviews("[]");
            report.setSummary(aiReply);
            report.setSuggestions("（无法结构化解析，请参考上方原始评估）");
        }
        return report;
    }

    private static final String SYSTEM_PROMPT = """
            你是一名资深技术面试评估专家。根据提供的完整面试记录（题目与候选人回答），
            输出一份评估报告，严格使用 JSON 格式，不要输出任何额外文字。JSON 结构：
            {
              "overall_score": 0-100 的整数总分,
              "qa_reviews": [
                {"question": "题目内容", "score": 0-100 每题得分, "comment": "该题点评"}
              ],
              "summary": "综合评语，评价整体表现与知识深度",
              "suggestions": "改进建议，2-4 条，具体可执行"
            }
            要求：点评客观具体，分数合理分布，全部使用中文。
            """;
}
