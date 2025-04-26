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
    private List<HistoryMessage> messages;

    /**
     * DTO for individual messages in a conversation.
     */
    @Data
    @Builder
    public static class HistoryMessage {
        private String role; // "user" or "assistant"
        private String content;
        private String timestamp; // ISO 8601 format
        private List<QueryResponse.SourceReference> sources; // Optional for assistant messages
    }
} 