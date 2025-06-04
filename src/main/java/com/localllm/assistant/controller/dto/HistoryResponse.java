package com.localllm.assistant.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * DTO for conversation history responses.
 */
@Data
@Builder
public class HistoryResponse {
    private String conversationId;
    private String title;
    private String createdAt;
    private String updatedAt;
    private List<HistoryMessage> messages;

    /**
     * DTO for individual messages in a conversation.
     */
    @Data
    @Builder
    public static class HistoryMessage {
        private String role;
        private String content;
        private String timestamp;
        private List<QueryResponse.SourceReference> sources;
    }
}
