package com.localllm.assistant.controller.dto;

import lombok.Data;

/**
 * Request DTO for query operations.
 * Contains the user's query text and optional conversation ID for continuing an existing conversation.
 */
@Data
public class QueryRequest {
    private String query;
    private String conversationId; // Optional
} 