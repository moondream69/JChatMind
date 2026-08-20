package com.moondream.jchatmind.service;

import com.moondream.jchatmind.model.request.CreateChatSessionRequest;
import com.moondream.jchatmind.model.request.UpdateChatSessionRequest;
import com.moondream.jchatmind.model.response.CreateChatSessionResponse;
import com.moondream.jchatmind.model.response.GetChatSessionResponse;
import com.moondream.jchatmind.model.response.GetChatSessionsResponse;

public interface ChatSessionFacadeService {
    GetChatSessionsResponse getChatSessions();

    GetChatSessionResponse getChatSession(String chatSessionId);

    GetChatSessionsResponse getChatSessionsByAgentId(String agentId);

    CreateChatSessionResponse createChatSession(CreateChatSessionRequest request);

    void deleteChatSession(String chatSessionId);

    void updateChatSession(String chatSessionId, UpdateChatSessionRequest request);
}
