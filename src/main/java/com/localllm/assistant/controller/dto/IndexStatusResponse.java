package com.localllm.assistant.controller.dto;

import lombok.Builder;
import lombok.Data;

/**
 * DTO for responses reporting the status of an indexing process.
 */
@Data
@Builder
public class IndexStatusResponse {
    private String jobId;
    private String status;
    private double progress;
    private String details;
} 
