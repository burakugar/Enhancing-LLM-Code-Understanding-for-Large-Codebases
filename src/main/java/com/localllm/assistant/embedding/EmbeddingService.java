package com.localllm.assistant.embedding;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.parser.model.CodeSegment;

/**
 * Interface for the service responsible for generating vector embeddings
 * for code segments or text using a local embedding model (via Ollama).
 */
public interface EmbeddingService {

    /**
     * Generates a vector embedding for a single text string asynchronously.
     *
     * @param text The text content to embed.
     * @return A CompletableFuture containing a List of Float representing the embedding vector.
     *         Completes exceptionally with EmbeddingException if generation fails.
     */
    CompletableFuture<List<Float>> generateEmbeddingAsync(String text);

    /**
     * Generates vector embeddings for a batch of CodeSegment objects asynchronously.
     * Implementations should handle batching requests to the embedding model if supported/efficient.
     *
     * @param segments A List of CodeSegment objects whose 'content' needs embedding.
     * @return A CompletableFuture containing a List of List<Float>, where each inner list
     *         is the embedding vector corresponding to the CodeSegment at the same index
     *         in the input list. Completes exceptionally if batch generation fails.
     *         Individual segment embedding errors should be handled (e.g., return null or empty list
     *         for that segment and log the error).
     */
    CompletableFuture<List<List<Float>>> generateEmbeddingsAsync(List<CodeSegment> segments);
} 