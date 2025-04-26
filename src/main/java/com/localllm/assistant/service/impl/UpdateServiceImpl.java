package com.localllm.assistant.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.parser.ParserService;
import com.localllm.assistant.service.FileMonitorService;
import com.localllm.assistant.service.UpdateService;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Service implementation that handles file change events from the file monitoring system,
 * processes the changes, and updates the vector store accordingly.
 */
@Service
@RequiredArgsConstructor
public class UpdateServiceImpl implements UpdateService {

    private static final Logger log = LoggerFactory.getLogger(UpdateServiceImpl.class);

    private final ParserService parserService;
    private final EmbeddingService embeddingService;
    private final VectorStoreClient vectorStoreClient;
    private final FileMonitorService fileMonitorService; // To get base path
    private final ChromaDBConfig chromaDBConfig;

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_FILE_MONITOR) // Use file monitor executor for quick tasks
    public void handleFileChange(Path filePath, FileMonitorService.ChangeType changeType) {
        log.info("Handling file change: Type={}, Path={}", changeType, filePath);
        Path basePath = fileMonitorService.getMonitoredPath();
        if (basePath == null) {
            log.error("Base path not available from FileMonitorService. Cannot process change for {}", filePath);
            return;
        }
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');

        switch (changeType) {
            case CREATE:
            case MODIFY:
                if (!Files.exists(filePath)) {
                    log.warn("File {} reported as {} but does not exist. Assuming delete or rapid change.", filePath, changeType);
                    // Treat as delete if it doesn't exist shortly after event
                    handleFileDelete(collectionName, relativePath);
                    return;
                }
                log.debug("Processing CREATE/MODIFY for {}", relativePath);
                parserService.parseFileAsync(filePath, basePath)
                    .thenCompose(segments -> {
                        if (segments == null || segments.isEmpty()) {
                            log.info("No segments parsed from {}. If file modified to be empty, existing entries might remain. Consider deletion.", relativePath);
                            // Optional: If MODIFY resulted in empty segments, treat as delete?
                            // handleFileDelete(collectionName, relativePath);
                            return CompletableFuture.completedFuture(null); // Indicate no further action needed for embedding/upsert
                        }
                        return embeddingService.generateEmbeddingsAsync(segments)
                            .thenAccept(embeddings -> {
                                if (embeddings == null || embeddings.size() != segments.size()) {
                                    log.error("Mismatch between segments ({}) and embeddings ({}) count for {}. Aborting upsert.", segments.size(), embeddings != null ? embeddings.size() : "null", relativePath);
                                    throw new RuntimeException("Embedding count mismatch"); // Fail the future
                                }
                                List<VectorEntry> entries = IntStream.range(0, segments.size())
                                    .filter(i -> embeddings.get(i) != null && !embeddings.get(i).isEmpty()) // Filter out failed embeddings
                                    .mapToObj(i -> VectorEntry.builder()
                                        .id(segments.get(i).getId())
                                        .embedding(embeddings.get(i))
                                        .metadata(Map.of( // Ensure metadata matches VectorEntry needs
                                            "filePath", segments.get(i).getRelativeFilePath(),
                                            "startLine", segments.get(i).getStartLine(),
                                            "endLine", segments.get(i).getEndLine(),
                                            "type", segments.get(i).getType().name(),
                                            "entityName", segments.get(i).getEntityName() != null ? segments.get(i).getEntityName() : ""
                                        ))
                                        .document(segments.get(i).getContent())
                                        .build())
                                    .collect(Collectors.toList());

                                if (!entries.isEmpty()) {
                                    log.debug("Upserting {} entries for {}", entries.size(), relativePath);
                                    vectorStoreClient.upsertEmbeddingsAsync(collectionName, entries)
                                        .exceptionally(ex -> {
                                            log.error("Failed to upsert embeddings for {}: {}", relativePath, ex.getMessage());
                                            return null; // Consume exception
                                        });
                                } else {
                                     log.warn("No valid embeddings generated for segments in {}. Nothing to upsert.", relativePath);
                                }
                            });
                    })
                    .exceptionally(ex -> {
                        log.error("Failed to process CREATE/MODIFY for {}: {}", relativePath, ex.getMessage());
                        return null; // Consume exception
                    });
                break;

            case DELETE:
                log.debug("Processing DELETE for {}", relativePath);
                handleFileDelete(collectionName, relativePath);
                break;
        }
    }

    /**
     * Helper method to handle file deletion by removing associated vector entries
     * 
     * @param collectionName the ChromaDB collection name
     * @param relativePath the relative path of the file being deleted
     */
    private void handleFileDelete(String collectionName, String relativePath) {
        // Helper for delete logic
        Map<String, Object> filter = Map.of("filePath", relativePath);
        vectorStoreClient.deleteEmbeddingsByMetadataAsync(collectionName, filter)
            .exceptionally(ex -> {
                log.error("Failed to delete embeddings for {}: {}", relativePath, ex.getMessage());
                return null; // Consume exception
            });
    }
} 
