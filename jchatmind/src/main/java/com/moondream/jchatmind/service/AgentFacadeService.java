package com.moondream.jchatmind.service;

import com.moondream.jchatmind.model.request.CreateAgentRequest;
import com.moondream.jchatmind.model.request.UpdateAgentRequest;
import com.moondream.jchatmind.model.response.CreateAgentResponse;
import com.moondream.jchatmind.model.response.GetAgentsResponse;

public interface AgentFacadeService {
    GetAgentsResponse getAgents();

    CreateAgentResponse createAgent(CreateAgentRequest request);

    void deleteAgent(String agentId);

    void updateAgent(String agentId, UpdateAgentRequest request);
}
