# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## 项目概览

JChatMind 是一个基于 Spring AI 的 Java Agent 系统：Think-Execute 循环 + 工具调用框架 + RAG 知识库（pgvector）+ 多模型切换 + SSE 实时推送。后端在 `jchatmind/`（Spring Boot 3.5.8 / Java 17 / MyBatis / PostgreSQL），前端在 `ui/`（React 19 / Ant Design 6 / Vite）。

## 环境要求（本机特殊性）

- **Java 17 必需**（pom 要求 17，项目实际使用 Spring Boot 3.5）：本机 PATH 默认是 JDK 1.8（`/e/Java/jdk1.8.0_301`），命令行构建/运行前必须切换，例如 `JAVA_HOME="/e/Java/jdk-17.0.9+8" PATH="/e/Java/jdk-17.0.9+8/bin:$PATH" mvn ...`。
- PostgreSQL + pgvector：默认连接 `localhost:5432/jchatmind`，账号 `root` / 密码 `123456`（仅本地开发）。
- Ollama 本地嵌入服务（`localhost:11434`，需 `ollama pull bge-m3`）——RAG 功能依赖它，Embedding 调用是同步阻塞的（`WebClient.block()`）。
- 模型 API Key 缺失时应用启动会失败——这是设计行为（宁可失败也不无 key 静默运行）。
- 前端依赖 `antd >= 6.6.1`：6.0.x 的 rc-slider 在 React 19 下高频拖动会触发 `Maximum update depth exceeded`（滑块崩溃），已升级修复——**不要降级**。

## 常用命令

```bash
# 后端：构建 / 运行 / 测试（全部需 JDK 17）
cd jchatmind
mvn spring-boot:run            # 启动，端口 8080
mvn test                       # 跑全部测试
mvn test -Dtest=JChatMindTests # 跑单个测试类

# 前端
cd ui
npm run dev                    # 开发服务器，端口 5173
npm run build                  # tsc 类型检查 + vite 构建
npm run lint                   # eslint
```

注意：`@SpringBootTest` 冒烟测试需要完整环境（PostgreSQL 在跑 + `.env` 存在），不是纯单元测试。

## 架构大图景

### 请求 → Agent 执行的链路（事件驱动，读 4 个文件才能看到全貌）

`ChatMessageController.createChatMessage()` → `ChatMessageFacadeServiceImpl`（保存 user 消息后**发布 ChatEvent**）→ `ChatEventListener`（`@EventListener` 异步）→ `JChatMindFactory.create(agentId, sessionId)` → `JChatMind.run()`。Agent 产出的每条消息再通过 `SseService` 推回前端。**不是同步调用链**——用户消息落库即返回，Agent 异步跑。

### Agent 运行时装配（JChatMindFactory）

`create()` 每次调用都从零装配一个 JChatMind 实例，全部来自数据库：
1. `loadAgent`：读 `agent` 表（system_prompt、model、allowed_tools / allowed_kbs / chat_options 三个 JSONB 字段）；
2. `loadMemory`：把 `chat_message` 表最近 N 条（`chat_options.messageLength`）恢复成 Spring AI 的 `Message` 列表（system/user/assistant+toolCalls/tool 四种角色转换）——实现断点续聊；
3. `resolveRuntimeTools`：固定工具（FIXED）+ 按 `allowed_tools` 启用的可选工具（OPTIONAL）；
4. `resolveRuntimeKnowledgeBases`：按 `allowed_kbs` 加载知识库描述（会拼进 thinkPrompt 告诉模型）；
5. `chatClientRegistry.get(agent.getModel())` 取模型实例。

### Think-Execute 循环（JChatMind.java）

- `think()`：把 `chatMemory` 全部历史 + 决策模块 system 提示（告知知识库列表）交给大模型，返回 `toolCalls`；
- `execute()`：`ToolCallingManager.executeToolCalls()` 手动执行工具——构造 ChatClient 时已显式关闭 Spring AI 内部自动执行（`internalToolExecutionEnabled(false)`），这是本项目刻意的手动接管设计；
- `MAX_STEPS = 20` 防无限循环；`terminate` 工具 → `FINISHED`；
- 每轮 assistant/tool 消息都 `saveMessage` 持久化 + `refreshPendingMessages` SSE 推送（`pendingChatMessages` 队列机制）。

### 工具框架

`Tool` 接口（getName/getDescription/getType）+ Spring AI 的 `@Tool` 注解方法 + `@Component`。`ToolFacadeServiceImpl` 注入 `List<Tool>` 自动收集，按 `ToolType` 分组。新增工具 = 新建一个类，核心循环零改动。`tools/test/` 下是实验工具（CityTool/DateTool/WeatherTool），`agent/examples/` 下是教学演进版本（V1/V2），均未被引用。

### 多模型注册表

`MultiChatClientConfig` 定义 ChatClient Bean（**Bean 名 = 模型名**，如 `deepseek-v4-flash`、`glm-4.6`）→ `ChatClientRegistry` 构造注入 `Map<String, ChatClient>` 自动收集。加新模型 = 加一个 Bean，Agent 的 `model` 字段填 Bean 名。

### RAG

`RagServiceImpl`：查询文本 → Ollama bge-m3 嵌入（1024 维）→ `ChunkBgeM3Mapper.similaritySearch`（`<->` L2 距离，top-3，ivfflat 索引）。向量类型靠 `PgVectorTypeHandler`（MyBatis 自定义 TypeHandler）+ `ChunkBgeM3Converter` 处理。对 Agent 而言知识库是**一个工具**（KnowledgeTool），模型缺上下文时主动调它。

### SSE

`SseServiceImpl`：`ConcurrentHashMap<chatSessionId, SseEmitter>` 管理连接（30 分钟超时，onCompletion/onTimeout/onError 清理）。`SseMessage` 6 种类型：`AI_GENERATED_CONTENT` / `AI_PLANNING` / `AI_THINKING` / `AI_EXECUTING` / `AI_DONE` / `AI_ERROR`。前端在 `ui/src/hooks/useChatSse.ts` 统一订阅（EventSource），`AgentChatView.tsx` 通过该 hook 接收并按类型渲染。Agent 正常结束发 `AI_DONE`，异常时落库一条错误消息并继续发 `AI_ERROR` 终态（`JChatMind.run()` 的 catch 分支）；状态推送（THINKING/EXECUTING）由 step() 每轮发射。单会话单连接——同一会话并发消息会互相覆盖连接。

## 配置体系

- `application.yaml` 中敏感项全部为 `${ENV}` 占位符，无默认值的关键项（模型 Key、邮箱）缺失即启动失败；
- 本地密钥在 `jchatmind/.env`（gitignore 忽略，模板 `.env.example`），通过 `spring.config.import` 的两条 optional 路径加载（`.env` 与 `./jchatmind/.env`），兼容 CLI（`cd jchatmind`，工作目录=`jchatmind/`）与 IDEA（工作目录=仓库根）两种启动方式——路径相对工作目录解析，找不到时静默跳过，若都找不到则 Key 缺失导致启动失败；**改 .env 后必须重启应用**（配置在启动时解析，无热加载）；
- 数据库账号密码有默认值兜底（`${DB_USERNAME:root}`），**不要**在新配置里把密钥写成 yaml 默认值。

## 分层约定

controller（薄）→ service（Facade 门面，接口在 `service/`、实现在 `service/impl/`）→ mapper（XML 在 `resources/mapper/`）。模型四件套：`entity`（DB 映射）/ `dto`（含 metadata 的完整模型）/ `vo`（前端视图）/ `request`、`response`（API 契约）。converter 负责 DTO↔VO 转换。统一响应 `ApiResponse{code, message, data}`，业务异常抛 `BizException` 由 `GlobalExceptionHandler` 兜底。**前后端契约**：`ui/src/api/http.ts` 硬编码 `BASE_URL = http://localhost:8080/api`，SSE 地址 `SSE_BASE_URL` 同样定义在 `http.ts`（前缀 `/sse`，不含 `/api`）。
