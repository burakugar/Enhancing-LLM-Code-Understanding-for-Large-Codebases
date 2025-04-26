package com.localllm.assistant.controller;

import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.controller.dto.QueryRequest;
import com.localllm.assistant.controller.dto.QueryResponse;
import com.localllm.assistant.service.QueryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for handling code-aware query operations.
 * Provides endpoints to process user queries against the indexed codebase.
 */
@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);
    private final QueryService queryService;

    /**
     * Processes a user query, searching for relevant code context and generating a response.
     *
     * @param request The query request containing the user's question and optional conversation ID
     * @return A CompletableFuture containing the response with answer and code references
     */
    @PostMapping
    public CompletableFuture<ResponseEntity<QueryResponse>> handleQuery(@RequestBody QueryRequest request) {
        log.info("Received query request for conversation ID: {}", request.getConversationId());
        
        if (request.getQuery() == null || request.getQuery().isBlank()) {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().build());
        }

        return queryService.processQuery(request.getQuery(), request.getConversationId())
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> {
                log.error("Error processing query: {}", ex.getMessage(), ex);
                return ResponseEntity.internalServerError().build();
            });
    }
} 
