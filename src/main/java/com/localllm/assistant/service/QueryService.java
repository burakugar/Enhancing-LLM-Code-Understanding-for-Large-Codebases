package com.localllm.assistant.service;

import com.localllm.assistant.controller.dto.QueryRequest;
import com.localllm.assistant.controller.dto.QueryResponse;

import java.util.concurrent.CompletableFuture;

public interface QueryService {

    /**
     * Processes a user query by generating embeddings, searching the vector store,
     * and then querying the LLM with the context.
     *
     * @param queryRequest The DTO containing all query parameters.
     * @return A CompletableFuture containing the QueryResponse.
     */
    CompletableFuture<QueryResponse> processQuery(QueryRequest queryRequest);

    /**
     * Processes a user query using default parameters for temperature, context size, etc.
     *
     * @param query The user's query string.
     * @param conversationId The ID of the conversation, can be null for a new conversation.
     * @return A CompletableFuture containing the QueryResponse.
     * @deprecated Prefer using {@link #processQuery(QueryRequest)} for more control.
     */
    @Deprecated
    CompletableFuture<QueryResponse> processQuery(String query, String conversationId);
}
