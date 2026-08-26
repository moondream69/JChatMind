package com.moondream.jchatmind.agent;

import com.moondream.jchatmind.model.dto.ChatMessageDTO;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@link JChatMindFactory#normalizeMemoryWindow} 单元测试。
 * 规范化策略：头部只丢弃半个工具对的痕迹，同时保证 assistant(toolCalls) 与 tool 响应成对闭合。
 */
class JChatMindFactoryTest {

    private static final String TOOL_CALL_ID = "call_001";

    private static ChatMessageDTO msg(ChatMessageDTO.RoleType role, String content) {
        return ChatMessageDTO.builder().role(role).content(content).build();
    }

    private static ChatMessageDTO user(String content) {
        return msg(ChatMessageDTO.RoleType.USER, content);
    }

    /** 带 toolCalls 的 assistant（工具对的开头） */
    private static ChatMessageDTO toolCallAssistant(String content) {
        return ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.ASSISTANT)
                .content(content)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolCalls(List.of(
                                new AssistantMessage.ToolCall(TOOL_CALL_ID, "function", "weather", "{}")))
                        .build())
                .build();
    }

    /** 带响应的 tool 消息（工具对的结尾） */
    private static ChatMessageDTO tool(String content) {
        return ChatMessageDTO.builder()
                .role(ChatMessageDTO.RoleType.TOOL)
                .content(content)
                .metadata(ChatMessageDTO.MetaData.builder()
                        .toolResponse(new ToolResponseMessage.ToolResponse("weather", "result", content))
                        .build())
                .build();
    }

    @Test
    void normalizeMemoryWindow_OrphanToolResultAtHead_TrimmedToUserMessage() {
        List<ChatMessageDTO> input = List.of(
                msg(ChatMessageDTO.RoleType.TOOL, "tool-result"),
                toolCallAssistant("assistant-toolcall"),
                msg(ChatMessageDTO.RoleType.TOOL, "tool-result"),
                user("之后的消息"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "回答"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(List.of(
                user("之后的消息"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "回答")), result);
    }

    @Test
    void normalizeMemoryWindow_HalfPairToolCallsAtHead_TrimmedToUserMessage() {
        List<ChatMessageDTO> input = List.of(
                toolCallAssistant("assistant-toolcall"),
                user("用户提问"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "回答"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(List.of(
                user("用户提问"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "回答")), result);
    }

    @Test
    void normalizeMemoryWindow_PureTextAssistantAtHead_Retained() {
        List<ChatMessageDTO> input = List.of(
                msg(ChatMessageDTO.RoleType.ASSISTANT, "上一轮的完整回答"),
                user("用户提问"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(input, result);
    }

    @Test
    void normalizeMemoryWindow_HeadIsSystemMessage_Retained() {
        List<ChatMessageDTO> input = List.of(
                msg(ChatMessageDTO.RoleType.SYSTEM, "system"),
                user("用户提问"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "回答"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(input, result);
    }

    @Test
    void normalizeMemoryWindow_CompleteToolPair_Retained() {
        List<ChatMessageDTO> input = List.of(
                user("查询北京天气"),
                toolCallAssistant("即将调用工具"),
                tool("北京晴天"),
                user("继续"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(input, result);
    }

    @Test
    void normalizeMemoryWindow_ConsecutiveToolPairs_AllRetained() {
        List<ChatMessageDTO> input = List.of(
                user("用户提问"),
                toolCallAssistant("第一次调用"),
                tool("结果1"),
                toolCallAssistant("第二次调用"),
                tool("结果2"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "汇总"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(input, result);
    }

    @Test
    void normalizeMemoryWindow_MultipleToolResponsesOnePair_Retained() {
        List<ChatMessageDTO> input = List.of(
                user("多工具查询"),
                toolCallAssistant("批量调用"),
                tool("结果1"),
                tool("结果2"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "汇总"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(input, result);
    }

    @Test
    void normalizeMemoryWindow_DanglingToolCallsBeforeErrorMessage_Dropped() {
        // 工具执行异常时的落库序列：[assistant(toolCalls), assistant(AI_ERROR 无 metadata)]
        List<ChatMessageDTO> input = List.of(
                user("触发工具"),
                toolCallAssistant("调用工具..."),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "AI 处理失败"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(List.of(
                user("触发工具"),
                msg(ChatMessageDTO.RoleType.ASSISTANT, "AI 处理失败")), result);
    }

    @Test
    void normalizeMemoryWindow_OrphanToolInMiddle_Dropped() {
        List<ChatMessageDTO> input = List.of(
                user("用户提问"),
                msg(ChatMessageDTO.RoleType.TOOL, "孤儿工具结果"),
                user("第二个提问"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertEquals(List.of(
                user("用户提问"),
                user("第二个提问")), result);
    }

    @Test
    void normalizeMemoryWindow_OnlyToolTraces_ReturnsEmpty() {
        List<ChatMessageDTO> input = List.of(
                msg(ChatMessageDTO.RoleType.TOOL, "tool-result"),
                toolCallAssistant("assistant-toolcall"),
                msg(ChatMessageDTO.RoleType.TOOL, "tool-result"));

        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(input);

        assertTrue(result.isEmpty());
    }

    @Test
    void normalizeMemoryWindow_EmptyInput_ReturnsEmpty() {
        List<ChatMessageDTO> result = JChatMindFactory.normalizeMemoryWindow(List.of());

        assertTrue(result.isEmpty());
    }
}
