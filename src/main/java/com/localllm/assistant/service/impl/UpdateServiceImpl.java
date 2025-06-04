package com.localllm.assistant.service.impl;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.parser.ParserService;
import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.service.FileMonitorService;
import com.localllm.assistant.service.UpdateService;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
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
    private final ChromaDBConfig chromaDBConfig;
    
    // Use Field Injection with @Lazy to break the cycle
    @Autowired
    @Lazy
    private FileMonitorService fileMonitorService; // No longer final
    
    // Debouncing configuration
    @Value("${update.debounce.delay.ms:1000}")
    private long debounceDelayMs;

    private final ScheduledExecutorService debounceExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "UpdateDebouncerThread");
        t.setDaemon(true);
        return t;
    });
    private final ConcurrentMap<Path, ScheduledFuture<?>> debouncedTasks = new ConcurrentHashMap<>();

    @Override
    public void handleFileChange(Path filePath, FileMonitorService.ChangeType changeType) {
        log.debug("Received file change event: Type={}, Path={}", changeType, filePath);

        ScheduledFuture<?> existingTask = debouncedTasks.remove(filePath);
        if (existingTask != null) {
            existingTask.cancel(false);
            log.trace("Cancelled previous debounced task for {}", filePath);
        }

        ScheduledFuture<?> newTask = debounceExecutor.schedule(() -> {
            try {
                log.info("Executing debounced update for: Type={}, Path={}", changeType, filePath);
                debouncedTasks.remove(filePath);
                processFileChangeInternal(filePath, changeType).join(); // Execute and wait
            } catch (Exception e) {
                 log.error("Error during debounced processing of {}: {}", filePath, e.getMessage(), e);
            }
        }, debounceDelayMs, TimeUnit.MILLISECONDS);

        debouncedTasks.put(filePath, newTask);
        log.trace("Scheduled debounced task for {}", filePath);
    }

    @Async(AsyncConfig.TASK_EXECUTOR_FILE_MONITOR)
    protected CompletableFuture<Void> processFileChangeInternal(Path filePath, FileMonitorService.ChangeType changeType) {
        // Ensure fileMonitorService is injected before proceeding
        if (fileMonitorService == null) {
             log.error("FileMonitorService is null! Circular dependency not fully resolved?");
             return CompletableFuture.failedFuture(new IllegalStateException("FileMonitorService not injected"));
        }

        Path basePath = fileMonitorService.getMonitoredPath(); // Now access the injected field
        if (basePath == null) {
            log.error("Base path not available from FileMonitorService. Cannot process change for {}", filePath);
            return CompletableFuture.failedFuture(new IllegalStateException("Base path not configured"));
        }
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        String relativePath = basePath.relativize(filePath).toString().replace('\\', '/');

        switch (changeType) {
            case CREATE:
            case MODIFY:
                if (!Files.exists(filePath)) {
                    log.warn("File {} reported as {} but does not exist during debounced processing. Treating as DELETE.", filePath, changeType);
                    return handleFileDelete(collectionName, relativePath);
                }
                log.debug("Processing CREATE/MODIFY for {}", relativePath);
                return parserService.parseFileAsync(filePath, basePath)
                        .thenCompose(segments -> {
                            if (segments == null || segments.isEmpty()) {
                                log.info("No segments parsed from {}. Deleting existing entries for this file.", relativePath);
                                return handleFileDelete(collectionName, relativePath);
                            }
                            return embeddingService.generateEmbeddingsAsync(segments)
                                    .thenCompose(embeddings -> {
                                        if (embeddings == null || embeddings.size() != segments.size()) {
                                            log.error("Mismatch between segments ({}) and embeddings ({}) count for {}. Aborting upsert.",
                                                    segments.size(), embeddings != null ? embeddings.size() : "null", relativePath);
                                            throw new RuntimeException("Embedding count mismatch during update");
                                        }
                                        List<VectorEntry> entries = mapSegmentsToEntries(segments, embeddings);
                                        if (entries.isEmpty()) {
                                             log.warn("No valid embeddings generated for segments in {}. Nothing to upsert.", relativePath);
                                             return CompletableFuture.completedFuture(null);
                                        }
                                        log.debug("Upserting {} entries for {}", entries.size(), relativePath);
                                        // Delete existing before upserting might be safer for MODIFY
                                        // return handleFileDelete(collectionName, relativePath)
                                        //        .thenCompose(v -> vectorStoreClient.upsertEmbeddingsAsync(collectionName, entries));
                                        return vectorStoreClient.upsertEmbeddingsAsync(collectionName, entries);
                                    });
                        })
                        .exceptionally(ex -> {
                            log.error("Failed to process CREATE/MODIFY update for {}: {}", relativePath, ex.getMessage(), ex);
                            return null;
                        });

            case DELETE:
                log.debug("Processing DELETE for {}", relativePath);
                return handleFileDelete(collectionName, relativePath);

            default:
                log.warn("Unhandled change type: {}", changeType);
                return CompletableFuture.completedFuture(null);
        }
    }

    private CompletableFuture<Void> handleFileDelete(String collectionName, String relativePath) {
        Map<String, Object> filter = Map.of("relativeFilePath", relativePath);
        log.info("Deleting entries for file '{}' from collection '{}'", relativePath, collectionName);
        return vectorStoreClient.deleteEmbeddingsByMetadataAsync(collectionName, filter)
                .exceptionally(ex -> {
                    log.error("Failed to delete embeddings for {}: {}", relativePath, ex.getMessage(), ex);
                    return null;
                });
    }

    private List<VectorEntry> mapSegmentsToEntries(List<CodeSegment> segments, List<List<Float>> embeddings) {
        return IntStream.range(0, segments.size())
                .filter(i -> segments.get(i) != null && segments.get(i).getId() != null &&
                             embeddings.get(i) != null && !embeddings.get(i).isEmpty())
                .mapToObj(i -> {
                    CodeSegment segment = segments.get(i);
                    Map<String, Object> metadata = createMetadataMap(segment);
                    return VectorEntry.builder()
                            .id(segment.getId())
                            .embedding(embeddings.get(i))
                            .metadata(metadata)
                            .document(segment.getContent())
                            .build();
                })
                .collect(Collectors.toList());
    }

     private Map<String, Object> createMetadataMap(CodeSegment segment) {
         Map<String, Object> metadata = new HashMap<>();
         metadata.put("id", segment.getId());
         metadata.put("relativeFilePath", segment.getRelativeFilePath());
         metadata.put("startLine", segment.getStartLine());
         metadata.put("endLine", segment.getEndLine());
         metadata.put("type", segment.getType().name());
         if (segment.getEntityName() != null) {
             metadata.put("entityName", segment.getEntityName());
         }
         if (segment.getMetadata() != null) {
             segment.getMetadata().forEach((key, value) -> metadata.putIfAbsent(key, value));
         }
         return metadata;
     }
}
