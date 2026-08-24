package com.moondream.jchatmind.event.listener;

import com.moondream.jchatmind.agent.JChatMind;
import com.moondream.jchatmind.agent.JChatMindFactory;
import com.moondream.jchatmind.event.ChatEvent;
import com.moondream.jchatmind.message.SseMessage;
import com.moondream.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@AllArgsConstructor
public class ChatEventListener {

    private final JChatMindFactory jChatMindFactory;
    private final SseService sseService;

    @Async
    @EventListener
    public void handle(ChatEvent event) {
        try {
            // 创建一个 Agent 实例处理聊天事件
            JChatMind jChatMind = jChatMindFactory.create(event.getAgentId(), event.getSessionId());
            jChatMind.run();
        } catch (Exception e) {
            // @Async 线程内的异常（含 run() 之前的工厂装配异常）无人接收，这里兜底：
            // 记录日志并尽力推送 AI_ERROR，前端才能收到"失败"信号（sending 才会复位）
            log.error("Failed to handle chat event, agentId: {}, sessionId: {}",
                    event.getAgentId(), event.getSessionId(), e);
            try {
                sseService.send(event.getSessionId(), SseMessage.builder()
                        .type(SseMessage.Type.AI_ERROR)
                        .payload(SseMessage.Payload.builder()
                                .statusText("AI 处理失败，请稍后重试")
                                .done(true)
                                .build())
                        .build());
            } catch (Exception ex) {
                log.warn("Failed to send AI_ERROR for sessionId: {}", event.getSessionId(), ex);
            }
        }
    }
}
