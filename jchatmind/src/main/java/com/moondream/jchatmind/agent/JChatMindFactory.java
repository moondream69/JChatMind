package com.moondream.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.moondream.jchatmind.agent.tools.Tool;
import com.moondream.jchatmind.config.ChatClientRegistry;
import com.moondream.jchatmind.converter.AgentConverter;
import com.moondream.jchatmind.converter.ChatMessageConverter;
import com.moondream.jchatmind.converter.KnowledgeBaseConverter;
import com.moondream.jchatmind.mapper.AgentMapper;
import com.moondream.jchatmind.mapper.KnowledgeBaseMapper;
import com.moondream.jchatmind.model.dto.AgentDTO;
import com.moondream.jchatmind.model.dto.ChatMessageDTO;
import com.moondream.jchatmind.model.dto.ChatMessageDTO.RoleType;
import com.moondream.jchatmind.model.dto.KnowledgeBaseDTO;
import com.moondream.jchatmind.model.entity.Agent;
import com.moondream.jchatmind.model.entity.KnowledgeBase;
import com.moondream.jchatmind.service.ChatMessageFacadeService;
import com.moondream.jchatmind.service.SseService;
import com.moondream.jchatmind.service.ToolFacadeService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;

    // 运行时 Agent 配置
    private AgentDTO agentConfig;

    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> chatMessages = normalizeMemoryWindow(
                chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength));
        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            ChatMessageDTO.MetaData metadata = chatMessageDTO.getMetadata();
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;
                case ASSISTANT:
                    // metadata 可能缺失（如 AI_ERROR 错误落库消息），此时仅保留文本内容
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(hasToolCalls(chatMessageDTO) ? metadata.getToolCalls() : List.of())
                            .build());
                    break;
                case TOOL:
                    // normalizeMemoryWindow 的本契约保证此处 TOOL 消息必携带 toolResponse
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(metadata.getToolResponse()))
                            .build());
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        return memory;
    }

    private static boolean hasToolCalls(ChatMessageDTO message) {
        return message.getMetadata() != null
                && message.getMetadata().getToolCalls() != null
                && !message.getMetadata().getToolCalls().isEmpty();
    }

    private static boolean hasToolResponse(ChatMessageDTO message) {
        return message.getMetadata() != null
                && message.getMetadata().getToolResponse() != null;
    }

    /**
     * 规范化记忆窗口切片为模型合法的历史序列（返回新列表，非视图）：
     * 1. 头部锚定：窗口可能从半个 Think-Execute 对（孤立的 TOOL 消息、缺失 tool 结果的
     *    ASSISTANT toolCalls）开始，丢弃这些半对痕迹直到锚定 USER / SYSTEM——
     *    纯文本 ASSISTANT（完整回答）不丢弃，避免误删合法上下文；
     * 2. 成对校验：assistant(toolCalls) 与后随的 0..n 条 tool 响应构成一个工具对，
     *    未接响应的悬空对（如工具执行异常后 AI_ERROR 落库的
     *    [assistant(toolCalls), assistant] 序列）整对丢弃；连续的工具对逐一保留。
     * 输出契约：被输出的 TOOL 消息必携带 toolResponse。
     */
    static List<ChatMessageDTO> normalizeMemoryWindow(List<ChatMessageDTO> chatMessages) {
        int start = 0;
        while (start < chatMessages.size() && !isHeadAnchor(chatMessages.get(start))) {
            start++;
        }
        List<ChatMessageDTO> result = new ArrayList<>();
        // 未闭合的工具对：首元素为 assistant(toolCalls)，之后追加 0..n 条 tool 响应
        List<ChatMessageDTO> openPair = new ArrayList<>();
        for (int i = start; i < chatMessages.size(); i++) {
            ChatMessageDTO message = chatMessages.get(i);
            RoleType role = message.getRole();
            if (role == RoleType.ASSISTANT && hasToolCalls(message)) {
                flushPair(openPair, result);
                openPair.add(message);
            } else if (role == RoleType.TOOL) {
                if (!openPair.isEmpty() && hasToolResponse(message)) {
                    openPair.add(message);
                }
            } else {
                flushPair(openPair, result);
                result.add(message);
            }
        }
        flushPair(openPair, result);
        return result;
    }

    /** 头部锚点：USER / SYSTEM / 无 toolCalls 的纯文本 ASSISTANT；其余视为半对痕迹 */
    private static boolean isHeadAnchor(ChatMessageDTO message) {
        RoleType role = message.getRole();
        return role == RoleType.USER || role == RoleType.SYSTEM
                || role == RoleType.ASSISTANT && !hasToolCalls(message);
    }

    /** 闭合工具对：含 tool 响应（size > 1）才保留，悬空的 [assistant(toolCalls)] 整对丢弃 */
    private static void flushPair(List<ChatMessageDTO> openPair, List<ChatMessageDTO> result) {
        if (openPair.size() > 1) {
            result.addAll(openPair);
        }
        openPair.clear();
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    private List<Tool> resolveRuntimeTools(AgentDTO agentConfig) {
        // 固定工具（系统强制）
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        // 可选工具（按 Agent 配置）
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames == null || allowedToolNames.isEmpty()) {
            return runtimeTools;
        }

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        for (String toolName : allowedToolNames) {
            Tool tool = optionalToolMap.get(toolName);
            if (tool != null) {
                runtimeTools.add(tool);
            }
        }
        return runtimeTools;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private JChatMind buildAgentRuntime(
            Agent agent,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new JChatMind(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter
        );
    }

    /**
     * 创建一个 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId) {
        Agent agent = loadAgent(agentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        List<Message> memory = loadMemory(chatSessionId);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 解析 agent 支持的工具调用
        List<Tool> runtimeTools = resolveRuntimeTools(agentConfig);
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(runtimeTools);

        return buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId
        );
    }
}
