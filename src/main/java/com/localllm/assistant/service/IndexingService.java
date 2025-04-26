package com.localllm.assistant.service;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

/**
 * Service responsible for managing the full indexing process of a codebase.
 */
public interface IndexingService {

    /**
     * Starts the full indexing process for the specified base path asynchronously.
     * Finds all relevant files, parses them, generates embeddings, and upserts them
     * into the vector store.
     *
     * @param basePath The root directory of the codebase to index.
     * @return A CompletableFuture that completes when indexing is finished or fails.
     *         Completes exceptionally with IndexingException if indexing cannot be started
     *         (e.g., already running) or if a critical error occurs.
     */
    CompletableFuture<Void> startIndexing(Path basePath);

    /**
     * Checks if an indexing process is currently active.
     *
     * @return true if indexing is in progress, false otherwise.
     */
    boolean isIndexingInProgress();

    // Optional: Add methods for status tracking, cancellation
    // IndexingStatus getIndexingStatus(String jobId);
    // boolean cancelIndexing(String jobId);
} 