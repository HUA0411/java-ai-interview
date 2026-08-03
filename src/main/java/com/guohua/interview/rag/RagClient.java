package com.guohua.interview.rag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * RAG 知识库服务客户端（对接 java-interview-rag 项目的 FastAPI /api/ask 接口）
 * <p>
 * 核心设计——降级：RAG 服务未启动/超时/报错时，返回 null，
 * 上层走纯 LLM 回答，保证面试流程不被知识库服务拖垮。
 */
@Slf4j
@Component
public class RagClient {

    private final String serviceUrl;
    private final RestClient client;
    private final ObjectMapper mapper = new ObjectMapper();

    public RagClient(@Value("${rag.service-url}") String serviceUrl) {
        this.serviceUrl = serviceUrl;
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(3000);
        factory.setReadTimeout(8000);
        this.client = RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("Content-Type", "application/json")
                .build();
    }

    /**
     * 检索与问题相关的知识点。
     *
     * @return 检索到的参考知识文本；RAG 服务不可用时返回 null（调用方降级）
     */
    public String retrieve(String question) {
        try {
            String resp = client.post()
                    .uri(serviceUrl + "/api/ask")
                    .body(Map.of("question", question))
                    .retrieve()
                    .body(String.class);

            JsonNode root = mapper.readTree(resp);
            JsonNode answer = root.path("answer");
            if (!answer.isMissingNode()) {
                return answer.asText();
            }
            // RAG 项目返回结构不同时，尝试取通用字段
            JsonNode data = root.path("data");
            if (data.isObject() && data.has("answer")) {
                return data.path("answer").asText();
            }
            return null;
        } catch (Exception e) {
            log.warn("RAG 服务不可用，降级为纯 LLM: {}", e.getMessage());
            return null;
        }
    }
}
