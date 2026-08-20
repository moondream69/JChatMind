package com.moondream.jchatmind.service;

import com.moondream.jchatmind.model.dto.ChatMessageDTO;
import com.moondream.jchatmind.model.request.CreateChatMessageRequest;
import com.moondream.jchatmind.model.request.UpdateChatMessageRequest;
import com.moondream.jchatmind.model.response.CreateChatMessageResponse;
import com.moondream.jchatmind.model.response.GetChatMessagesResponse;

import java.util.List;

public interface ChatMessageFacadeService {
    GetChatMessagesResponse getChatMessagesBySessionId(String sessionId);

    List<ChatMessageDTO> getChatMessagesBySessionIdRecently(String sessionId, int limit);

    CreateChatMessageResponse createChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse createChatMessage(ChatMessageDTO chatMessageDTO);

    CreateChatMessageResponse agentCreateChatMessage(CreateChatMessageRequest request);

    CreateChatMessageResponse appendChatMessage(String chatMessageId, String appendContent);

    void deleteChatMessage(String chatMessageId);

    void updateChatMessage(String chatMessageId, UpdateChatMessageRequest request);
}
