package com.localllm.assistant.controller.dto;

import lombok.Builder;
import lombok.Data;

/**
 * DTO for responses reporting the status of an indexing process.
 */
@Data
@Builder
public class IndexStatusResponse {
    private String jobId; // If tracking specific jobs
    private String status; // e.g., IDLE, RUNNING, STARTED, COMPLETED, FAILED
    private double progress; // e.g., 0.75 for 75% complete
    private String details; // Additional status information or error details
} 