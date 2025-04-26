package com.localllm.assistant.vectorstore;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.vectorstore.model.VectorEntry;

/**
 * Interface for interacting with a vector database for storing and retrieving embeddings.
 * All operations are asynchronous and return CompletableFuture.
 */
public interface VectorStoreClient {

    /**
     * Ensures that a collection exists in the vector store.
     * If the collection doesn't exist, it will be created.
     *
     * @param collectionName the name of the collection to ensure exists
     * @return a CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> ensureCollectionExists(String collectionName);

    /**
     * Upserts (inserts or updates) embeddings into the vector store.
     *
     * @param collectionName the name of the collection to upsert into
     * @param entries the list of entries to upsert
     * @return a CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> upsertEmbeddingsAsync(String collectionName, List<VectorEntry> entries);

    /**
     * Searches for similar embeddings in the vector store.
     *
     * @param collectionName the name of the collection to search in
     * @param queryEmbedding the embedding vector to search for similarity with
     * @param k the number of most similar results to return
     * @param metadataFilter optional filter to apply based on metadata (can be null)
     * @return a CompletableFuture with the list of similar vector entries
     */
    CompletableFuture<List<VectorEntry>> searchSimilarEmbeddingsAsync(
            String collectionName,
            List<Float> queryEmbedding,
            int k,
            Map<String, Object> metadataFilter);

    /**
     * Deletes embeddings from the vector store based on metadata.
     *
     * @param collectionName the name of the collection to delete from
     * @param metadataFilter the metadata filter to match entries to delete
     * @return a CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> deleteEmbeddingsByMetadataAsync(
            String collectionName, Map<String, Object> metadataFilter);

    /**
     * Deletes embeddings from the vector store based on their IDs.
     *
     * @param collectionName the name of the collection to delete from
     * @param ids the list of IDs to delete
     * @return a CompletableFuture that completes when the operation is done
     */
    CompletableFuture<Void> deleteEmbeddingsByIdsAsync(String collectionName, List<String> ids);

    /**
     * Counts the total number of embeddings in a collection.
     *
     * @param collectionName the name of the collection to count
     * @return a CompletableFuture with the count of embeddings
     */
    CompletableFuture<Long> countEmbeddingsAsync(String collectionName);
} 