package com.moondream.jchatmind.agent.examples;

import com.moondream.jchatmind.agent.tools.test.CityTool;
import com.moondream.jchatmind.agent.tools.test.DateTool;
import com.moondream.jchatmind.agent.tools.test.WeatherTool;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * JChatMindV2 测试类
 * 测试工具调用功能（ReAct 模型）
 */
@SpringBootTest
public class JChatMindV2Test {

    @Autowired
    @Qualifier("deepseek-v4-flash")
    private ChatClient chatClient;

    @Autowired
    private CityTool cityTool;

    @Autowired
    private DateTool dateTool;

    @Autowired
    private WeatherTool weatherTool;

    @Test
    public void testToolCalling() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息。",
                chatClient,
                20,
                "test-session-v2",
                Arrays.asList(toolCallbacks)
        );

        // 测试需要调用工具的对话
        String userInput = "介绍一下你自己？";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testMultipleToolCalls() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息。",
                chatClient,
                20,
                "test-session-v2-multi",
                Arrays.asList(toolCallbacks)
        );

        // 测试需要调用多个工具的对话
        String userInput = "请告诉我今天的日期和当前城市，然后查询这个城市今天的天气。";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
        for (int i = 0; i < agent.getConversationHistory().size(); i++) {
            System.out.println(agent.getConversationHistory().get(i));
            if (i < agent.getConversationHistory().size() - 1) {
                System.out.println("----------");
            }
        }
    }

    @Test
    public void testConversationWithoutToolCalling() {
        // 创建 V2 实例（不提供工具）
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一只蓝色大肥鱼。",
                chatClient,
                20,
                "test-session-v2-no-tool",
                List.of() // 不提供工具
        );

        // 测试不需要工具的普通对话
        String userInput = "如果你的上下文中有两个SystemMessage，你会看哪一个";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }

    @Test
    public void testReActLoop() {
        // 准备工具回调
        ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                .toolObjects(cityTool, dateTool, weatherTool)
                .build()
                .getToolCallbacks();

        // 创建 V2 实例
        JChatMindV2 agent = new JChatMindV2(
                "test-agent-v2",
                "测试 Agent V2",
                "你是一个智能助手，可以帮助用户查询天气、日期和城市信息。",
                chatClient,
                20,
                "test-session-v2-react",
                Arrays.asList(toolCallbacks)
        );

        // 测试 ReAct 循环（think-execute）
        String userInput = "我想知道今天的天气，请先告诉我今天的日期。";
        String response = agent.chat(userInput);

        // 验证回复不为空
        assertNotNull(response);
        assertTrue(response.length() > 0);

        System.out.println("用户输入: " + userInput);
        System.out.println("AI 回复: " + response);
        System.out.println("对话历史长度: " + agent.getConversationHistory().size());
    }
}

