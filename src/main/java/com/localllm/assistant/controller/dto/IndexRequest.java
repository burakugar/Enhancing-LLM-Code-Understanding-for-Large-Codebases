package com.localllm.assistant.controller.dto;

import lombok.Data;

/**
 * DTO for requests to start the indexing process.
 * Contains the path to the codebase to be indexed.
 */
@Data
public class IndexRequest {
    private String codebasePath;
} 