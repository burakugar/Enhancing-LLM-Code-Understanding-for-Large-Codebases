package com.localllm.assistant.service;

import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.controller.dto.QueryResponse;

/**
 * Service responsible for processing user queries, interacting with LLM and vector store,
 * and managing conversation context.
 */
public interface QueryService {

    /**
     * Processes a user query asynchronously.
     * Retrieves context, calls LLM, saves history, and returns the response.
     *
     * @param query          The user's query text.
     * @param conversationId Optional ID of an existing conversation.
     * @return A CompletableFuture containing the QueryResponse DTO.
     */
    CompletableFuture<QueryResponse> processQuery(String query, String conversationId);

} 