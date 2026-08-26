package com.moondream.jchatmind.agent;

import com.moondream.jchatmind.converter.ChatMessageConverter;
import com.moondream.jchatmind.message.SseMessage;
import com.moondream.jchatmind.model.dto.ChatMessageDTO;
import com.moondream.jchatmind.model.dto.KnowledgeBaseDTO;
import com.moondream.jchatmind.model.vo.ChatMessageVO;
import com.moondream.jchatmind.service.ChatMessageFacadeService;
import com.moondream.jchatmind.service.SseService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        // 内存层不设窗口上限(截断唯一由 JChatMindFactory.loadMemory 按 messageLength 控制并规范化):
        // 若内存层也按条数截断,工具调用对(assistant(toolCalls) -> tool)可能被从中间切开,
        // 产生孤儿 tool 消息,部分模型 API(DeepSeek/glm-4.6)会拒绝:HTTP 400
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(Integer.MAX_VALUE)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示(保持在 memory 之后:MessageWindowChatMemory 的 process() 会对“新加入的
        // SystemMessage”做 hasNewSystemMessage 去重——若 memory 中含 DB 恢复的 system 行,
        // agent 配置的 systemPrompt 必须是后由新加入的那一条才能生效)
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            chatMessageFacadeService.createChatMessage(chatMessageDTO);
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                chatMessageFacadeService.createChatMessage(chatMessageDTO);
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean think() {
        String thinkPrompt = """
                现在你是一个智能的具体「决策模块」
                请根据当前对话上下文，决定下一步的动作。
                                \s
                【额外信息】
                - 你目前拥有的知识库列表以及描述：%s
                - 如果有缺失的上下文时，优先从知识库中进行搜索
                """.formatted(this.availableKbs);

        // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
        // 既能让每次 messageList 的最后一条是 本条提示词，
        // 又能够避免将 thinkPrompt 加入到聊天记录中
        Prompt prompt = Prompt.builder()
                .chatOptions(this.chatOptions)
                .messages(this.chatMemory.get(this.chatSessionId))
                .build();

        this.lastChatResponse = this.chatClient
                .prompt(prompt)
                .system(thinkPrompt)
                .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                .call()
                .chatClientResponse()
                .chatResponse();

        Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

        AssistantMessage output = this.lastChatResponse
                .getResult()
                .getOutput();

        List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

        // 保存
        saveMessage(output);
        refreshPendingMessages();

        // 打印工具调用
        logToolCalls(toolCalls);

        // 如果工具调用不为空，则进入执行阶段
        return !toolCalls.isEmpty();
    }

    // 执行
    private void execute() {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        Prompt prompt = Prompt.builder()
                .messages(this.chatMemory.get(this.chatSessionId))
                .chatOptions(this.chatOptions)
                .build();

        ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

        this.chatMemory.clear(this.chatSessionId);
        this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

        ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                .conversationHistory()
                .get(toolExecutionResult.conversationHistory().size() - 1);

        String collect = toolResponseMessage.getResponses()
                .stream()
                .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                .collect(Collectors.joining("\n"));

        log.info("工具调用结果：{}", collect);

        // 保存工具调用
        saveMessage(toolResponseMessage);
        refreshPendingMessages();

        if (toolResponseMessage.getResponses()
                .stream()
                .anyMatch(resp -> resp.name().equals("terminate"))) {
            this.agentState = AgentState.FINISHED;
            log.info("任务结束");
        }
    }

    // 单个步骤模板
    private void step() {
        sendStatus(SseMessage.Type.AI_THINKING, "AI 正在思考决策...");
        if (think()) {
            sendStatus(SseMessage.Type.AI_EXECUTING, "AI 正在执行工具调用...");
            execute();
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    // 发送过程状态信号（AI_THINKING / AI_EXECUTING），推送是尽力而为，不得影响主流程
    private void sendStatus(SseMessage.Type type, String statusText) {
        try {
            SseMessage sseMessage = SseMessage.builder()
                    .type(type)
                    .payload(SseMessage.Payload.builder()
                            .statusText(statusText)
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        } catch (Exception e) {
            log.warn("Failed to send Sse status message, type: {}", type, e);
        }
    }

    // 发送终态信号（AI_DONE / AI_ERROR），推送是尽力而为，不得影响主流程
    private void sendFinalMessage(SseMessage.Type type) {
        try {
            SseMessage sseMessage = SseMessage.builder()
                    .type(type)
                    .payload(SseMessage.Payload.builder()
                            .statusText(type == SseMessage.Type.AI_ERROR ? "AI 处理失败" : "处理完成")
                            .done(true)
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        } catch (Exception e) {
            log.warn("Failed to send Sse final message, type: {}", type, e);
        }
    }

    // 异常时落库一条 assistant 错误消息，让前端与历史记录都能看到失败原因
    private void persistErrorMessage(Exception e) {
        try {
            String content = "AI 处理失败："
                    + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            ChatMessageDTO errorDTO = ChatMessageDTO.builder()
                    .role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(content)
                    .sessionId(this.chatSessionId)
                    .build();
            chatMessageFacadeService.createChatMessage(errorDTO);
            refreshPendingMessages();
        } catch (Exception ex) {
            log.error("Failed to persist error message", ex);
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                int currentStep = i + 1;
                step();
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            log.error("Error running agent", e);
            // 落库错误消息并推送（前端能看到失败原因），再发送错误终态信号
            persistErrorMessage(e);
            sendFinalMessage(SseMessage.Type.AI_ERROR);
            return;
        }
        // 正常结束，发送完成终态信号
        sendFinalMessage(SseMessage.Type.AI_DONE);
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
