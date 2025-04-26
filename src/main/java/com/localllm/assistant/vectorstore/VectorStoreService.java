package com.localllm.assistant.vectorstore;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.parser.model.CodeSegment;

/**
 * Service interface for vector storage operations.
 * Handles storing and retrieving code segments based on vector embeddings for similarity search.
 */
public interface VectorStoreService {

    /**
     * Stores a code segment with its embedding vector in the vector store.
     *
     * @param segment The code segment to store
     * @param embedding The embedding vector associated with the segment
     * @return A future that completes when the segment has been stored
     */
    CompletableFuture<Void> storeSegmentAsync(CodeSegment segment, List<Float> embedding);

    /**
     * Stores multiple code segments with their associated embedding vectors.
     *
     * @param segments List of code segments to store
     * @param embeddings List of embedding vectors corresponding to each segment
     * @return A future that completes when all segments have been stored
     */
    CompletableFuture<Void> storeSegmentsAsync(List<CodeSegment> segments, List<List<Float>> embeddings);

    /**
     * Searches for code segments similar to the provided query text.
     *
     * @param queryEmbedding The embedding vector of the query text
     * @param maxResults Maximum number of results to return
     * @param minScore Minimum similarity score threshold (0.0 to 1.0)
     * @return A future completing with a list of results ordered by similarity score
     */
    CompletableFuture<List<VectorSearchResult>> searchSimilarAsync(List<Float> queryEmbedding, int maxResults, double minScore);

    /**
     * Retrieves a code segment by its unique ID.
     *
     * @param segmentId The unique ID of the code segment
     * @return A future completing with the code segment if found
     */
    CompletableFuture<Optional<CodeSegment>> getSegmentByIdAsync(String segmentId);

    /**
     * Deletes a code segment by its unique ID.
     *
     * @param segmentId The unique ID of the code segment to delete
     * @return A future completing with true if the segment was deleted, false if not found
     */
    CompletableFuture<Boolean> deleteSegmentAsync(String segmentId);

    /**
     * Checks if the vector store contains a segment with the specified ID.
     *
     * @param segmentId The unique ID of the code segment
     * @return A future completing with true if the segment exists, false otherwise
     */
    CompletableFuture<Boolean> containsSegmentAsync(String segmentId);
} 