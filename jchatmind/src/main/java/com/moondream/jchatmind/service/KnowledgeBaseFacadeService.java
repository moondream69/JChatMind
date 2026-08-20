package com.moondream.jchatmind.service;

import com.moondream.jchatmind.model.request.CreateKnowledgeBaseRequest;
import com.moondream.jchatmind.model.request.UpdateKnowledgeBaseRequest;
import com.moondream.jchatmind.model.response.CreateKnowledgeBaseResponse;
import com.moondream.jchatmind.model.response.GetKnowledgeBasesResponse;

public interface KnowledgeBaseFacadeService {
    GetKnowledgeBasesResponse getKnowledgeBases();

    CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request);

    void deleteKnowledgeBase(String knowledgeBaseId);

    void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request);
}

