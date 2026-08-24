package com.moondream.jchatmind.model.vo;

import com.moondream.jchatmind.model.dto.ChatMessageDTO;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChatMessageVO {
    private String id;
    private String sessionId;
    private ChatMessageDTO.RoleType role;
    private String content;
    private ChatMessageDTO.MetaData metadata;
    private LocalDateTime createdAt;
}
