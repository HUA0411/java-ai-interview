package com.guohua.interview.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.guohua.interview.common.BizException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/**
 * DeepSeek API 客户端（OpenAI 兼容协议，原生 RestClient 调用，未引入 Spring AI）
 * <p>
 * 负责：组装 messages → 调 /chat/completions → 提取回答文本。
 * 错误处理：网络/限流等异常统一转成 BizException(502)，由上层决定重试或降级。
 */
@Slf4j
@Component
@ConfigurationProperties(prefix = "deepseek")
public class DeepSeekClient {

    /** 单条消息：{"role": "system|user|assistant", "content": "..."} */
    public record ChatMessage(String role, String content) {
    }

    private String apiKey;
    private String baseUrl = "https://api.deepseek.com";
    private String model = "deepseek-chat";

    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepSeekClient() {
        // 5s 连接超时，90s 读取超时（长回答需要）
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(5000);
        factory.setReadTimeout(90000);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /** 非流式对话：返回 AI 回答文本 */
    public String chat(List<ChatMessage> messages) {
        if (apiKey == null || apiKey.isBlank()) {
            throw BizException.badRequest("服务端未配置 DEEPSEEK_API_KEY");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "temperature", 0.7,
                "stream", false);

        try {
            String resp = client.post()
                    .uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(resp);
            String content = root.path("choices").path(0).path("message").path("content").asText();
            if (content.isBlank()) {
                throw BizException.badRequest("AI 返回内容为空");
            }
            return content;
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("DeepSeek 调用失败", e);
            throw new BizException(502, "AI 服务调用失败，请稍后重试");
        }
    }

    /**
     * 流式对话：逐段回调增量内容（SSE 解析）。
     * DeepSeek 返回 OpenAI 兼容的 SSE 流：data: {"choices":[{"delta":{"content":"..."}}]}
     */
    public void chatStream(List<ChatMessage> messages, Consumer<String> onDelta) {
        if (apiKey == null || apiKey.isBlank()) {
            throw BizException.badRequest("服务端未配置 DEEPSEEK_API_KEY");
        }
        Map<String, Object> body = Map.of(
                "model", model,
                "messages", messages.stream().map(m -> Map.of("role", m.role(), "content", m.content())).toList(),
                "temperature", 0.7,
                "stream", true);

        try {
            client.post()
                    .uri(baseUrl + "/chat/completions")
                    .header("Authorization", "Bearer " + apiKey)
                    .body(body)
                    .exchange((request, response) -> consumeSseStream(response, onDelta));
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.error("DeepSeek 流式调用失败", e);
            throw new BizException(502, "AI 服务调用失败，请稍后重试");
        }
    }

    /** 逐行读取 SSE 流，把增量内容回调出去；[DONE] 结束（RestClient 的 exchange 只允许 IOException，其余异常在此消化） */
    private Object consumeSseStream(ClientHttpResponse response, Consumer<String> onDelta) {
        try {
            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new BizException(502, "AI 服务返回异常状态: " + response.getStatusCode());
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(response.getBody(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    String data = line.substring(5).trim();
                    if (data.equals("[DONE]")) {
                        break;
                    }
                    JsonNode root = mapper.readTree(data);
                    JsonNode delta = root.path("choices").path(0).path("delta").path("content");
                    if (!delta.isMissingNode()) {
                        onDelta.accept(delta.asText());
                    }
                }
            }
        } catch (Exception e) {
            // BizException 原样上抛，其余转 502
            if (e instanceof BizException biz) {
                throw biz;
            }
            throw new BizException(502, "AI 流式响应解析失败: " + e.getMessage());
        }
        return null;
    }

    // ---------- 配置注入（字段注入，配合 @ConfigurationProperties） ----------

    public void setApiKey(String apiKey) {
        this.apiKey = apiKey;
    }

    public void setBaseUrl(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public void setModel(String model) {
        this.model = model;
    }
}
