package com.moondream.jchatmind.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.moondream.jchatmind.message.SseMessage;
import com.moondream.jchatmind.service.SseService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Slf4j
@Service
@AllArgsConstructor
public class SseServiceImpl implements SseService {

    private final ConcurrentMap<String, SseEmitter> clients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Override
    public SseEmitter connect(String chatSessionId) {
        SseEmitter emitter = new SseEmitter(30 * 60 * 1000L);
        clients.put(chatSessionId, emitter);

        try {
            emitter.send(SseEmitter.event()
                    .name("init")
                    .data("connected")
            );
        } catch (IOException e) {
            throw new RuntimeException(e);
        }

        emitter.onCompletion(() -> {
            clients.remove(chatSessionId);
        });
        emitter.onTimeout(() -> clients.remove(chatSessionId));
        emitter.onError((error) -> clients.remove(chatSessionId));

        return emitter;
    }

    @Override
    public void send(String chatSessionId, SseMessage message) {
        SseEmitter emitter = clients.get(chatSessionId);

        if (emitter == null) {
            // 客户端未连接，推送是尽力而为（消息已落库，前端可通过拉取恢复）
            log.warn("No SSE client for chatSessionId: {}, message dropped", chatSessionId);
            return;
        }

        try {
            // 将消息转换为字符串
            String sseMessageStr = objectMapper.writeValueAsString(message);
            emitter.send(SseEmitter.event()
                    .name("message")
                    .data(sseMessageStr)
            );
        } catch (IOException e) {
            // 连接已断开，移除并记录，不影响调用方（Agent 主流程）
            log.warn("Failed to send SSE message to chatSessionId: {}", chatSessionId, e);
            clients.remove(chatSessionId);
        }
    }
}
