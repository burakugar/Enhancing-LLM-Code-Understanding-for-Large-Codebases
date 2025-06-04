package com.localllm.assistant.controller.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;

/**
 * Response DTO for query operations.
 * Contains the assistant's answer, any referenced sources, and the conversation ID.
 */
@Data
@Builder
public class QueryResponse {
    private String answer;
    private List<SourceReference> sources;
    private String conversationId;

    /**
     * Represents a code reference used as a source for the response.
     */
    @Data
    @Builder
    public static class SourceReference {
        private String filePath;
        private int startLine;
        private int endLine;
        private String snippet;
        private double score;
    }
} 
