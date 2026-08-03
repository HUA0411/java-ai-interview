# AI 面试模拟系统（AI Interview Simulator）

基于 **Spring Boot 3 + DeepSeek API** 的 Java 全栈面试模拟应用：选择方向 → AI 逐题提问 → 流式作答 → 生成结构化评估报告。

与 [java-interview-rag](https://github.com/HUA0411/java-interview-rag)（Java 面试知识库 RAG 问答系统）形成完整链路：**知识库 → 问答 → 模拟面试**。面试提问与点评会调用本地 RAG 服务检索知识点注入 Prompt，RAG 不可用时自动降级为纯 LLM。

## 技术栈

| 层 | 技术 |
|---|---|
| 后端 | Spring Boot 3.3 · Java 22 · MyBatis-Plus · MySQL 8 |
| 认证 | JWT（jjwt）+ BCrypt 密码加密 + 拦截器 |
| AI | DeepSeek API（原生 RestClient，未引入 Spring AI）· SSE 流式输出 · 虚拟线程 |
| 前端 | 原生 HTML / CSS / JavaScript（无框架） |
| 其他 | Lombok · 全局异常处理 · 统一返回结构 |

## 功能

- **用户认证**：注册 / 登录（JWT），密码 BCrypt 加密
- **模拟面试**：三方向（Java 后端 / 前端 / AI 应用），AI 逐题提问，回答后点评并追问，共 5 题
- **流式输出**：SSE 打字机效果；断线时已生成内容自动落库，刷新不丢消息
- **RAG 增强**：提问/点评前调用本地 RAG 知识库检索知识点注入 Prompt；RAG 服务离线自动降级纯 LLM
- **评估报告**：面试结束异步生成总分 + 逐题点评 + 综合评语 + 改进建议（AI 输出 JSON 解析，失败降级纯文本）
- **历史记录**：会话列表 + 报告详情（报告生成中自动轮询）

## 快速启动

### 1. 环境要求

- JDK 17+（推荐 21+，流式输出使用虚拟线程）
- Maven 3.9+
- MySQL 8（root 账号，或按需调整配置）

### 2. 建库

```sql
CREATE DATABASE IF NOT EXISTS ai_interview DEFAULT CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci;
```

（表结构由 `src/main/resources/schema.sql` 启动时自动创建，幂等可重复执行）

### 3. 配置（本地开发，不提交 git）

创建 `src/main/resources/application-local.yml`（参考同目录 `application.yml` 模板）：

```yaml
spring:
  datasource:
    password: <你的 MySQL 密码>
deepseek:
  api-key: <你的 DeepSeek API Key>
jwt:
  secret: <随机字符串，至少 32 位>
```

### 4. 启动

```bash
mvn spring-boot:run -Dspring-boot.run.profiles=local
```

访问 http://localhost:8080

### 5.（可选）对接 RAG 知识库

启动 [java-interview-rag](https://github.com/HUA0411/java-interview-rag) 的 FastAPI 服务（默认 `localhost:8000`），
面试提问与点评会自动注入知识库检索结果；服务未启动时自动降级为纯 LLM，不影响使用。

## 项目结构

```
src/main/java/com/guohua/interview/
├── auth/       # 用户认证：实体/Mapper/Service/JWT
├── session/    # 面试会话：实体/Mapper/编排服务/接口
├── report/     # 评估报告：实体/Mapper/AI 生成服务
├── rag/        # RAG 客户端（对接知识库服务 + 降级）
├── ai/         # DeepSeek 客户端（流式/非流式）
├── config/     # JWT 拦截器、Web 配置
└── common/     # 统一返回、全局异常
src/main/resources/static/  # 前端页面（登录/面试/历史）
```

## 设计要点

1. **会话上下文控制**：传给大模型的历史消息只保留最近 10 条，控制 token 成本（与 RAG 项目思路一致）
2. **服务降级**：RAG 知识库服务 3s 连接超时 + 8s 读取超时，失败返回 null 走纯 LLM，面试流程不被外部服务拖垮
3. **断线不丢消息**：SSE 流式输出中客户端断开时，已生成内容仍会落库
4. **异步解耦**：报告生成使用虚拟线程异步执行，不阻塞面试流收尾，前端轮询获取
5. **AI 输出容错**：报告 JSON 解析失败时降级为纯文本，保证功能可用
