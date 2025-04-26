package com.localllm.assistant.service.impl;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.exception.IndexingException;
import com.localllm.assistant.parser.ParserService;
import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.service.IndexingService;
import com.localllm.assistant.util.FileUtils;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Implementation of IndexingService that handles the full process of finding,
 * parsing, embedding, and storing code segments from a codebase.
 */
@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final ParserService parserService;
    private final EmbeddingService embeddingService;
    private final VectorStoreClient vectorStoreClient;
    private final ChromaDBConfig chromaDBConfig;

    // Flag to track if indexing is currently in progress
    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<Void> startIndexing(Path basePath) {
        if (!Files.isDirectory(basePath)) {
            return CompletableFuture.failedFuture(
                new IndexingException("Provided path is not a directory: " + basePath));
        }

        // Ensure we don't start multiple indexing jobs
        if (!indexingInProgress.compareAndSet(false, true)) {
            return CompletableFuture.failedFuture(
                new IndexingException("Indexing is already in progress"));
        }

        String collectionName = chromaDBConfig.getDefaultCollectionName();
        final AtomicInteger fileCount = new AtomicInteger(0);
        final AtomicInteger segmentCount = new AtomicInteger(0);
        final AtomicInteger entryCount = new AtomicInteger(0);

        log.info("Starting indexing process for codebase at: {}", basePath);
        
        return vectorStoreClient.ensureCollectionExists(collectionName)
            .thenCompose(v -> {
                try {
                    // Find all Java files in the codebase
                    List<Path> javaFiles = FileUtils.findAllJavaFiles(basePath);
                    fileCount.set(javaFiles.size());
                    log.info("Found {} Java files to index", javaFiles.size());
                    
                    if (javaFiles.isEmpty()) {
                        log.warn("No Java files found in the directory: {}", basePath);
                        return CompletableFuture.completedFuture(Collections.<CodeSegment>emptyList());
                    }
                    
                    // Parse all files
                    return parserService.parseFilesAsync(javaFiles, basePath);
                    
                } catch (IOException e) {
                    throw new IndexingException("Failed to find source files in " + basePath, e);
                }
            })
            .thenCompose(segments -> {
                segmentCount.set(segments.size());
                log.info("Successfully parsed {} files into {} code segments", fileCount.get(), segments.size());
                
                if (segments.isEmpty()) {
                    log.warn("No code segments extracted during parsing");
                    return CompletableFuture.completedFuture(null);
                }
                
                // Generate embeddings for all segments
                return embeddingService.generateEmbeddingsAsync(segments)
                    .thenCompose(embeddings -> {
                        if (embeddings == null || embeddings.size() != segments.size()) {
                            log.error("Embedding count mismatch during indexing. Segments: {}, Embeddings: {}", 
                                segments.size(), embeddings != null ? embeddings.size() : "null");
                            throw new IndexingException("Embedding count mismatch");
                        }
                        
                        // Create vector entries from segments and embeddings
                        List<VectorEntry> entries = createVectorEntries(segments, embeddings);
                        entryCount.set(entries.size());
                        log.info("Generated {} valid embeddings. Starting upsert in batches.", entries.size());
                        
                        // Process in batches to avoid overwhelming the vector store
                        int batchSize = 200;
                        List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
                        
                        for (int i = 0; i < entries.size(); i += batchSize) {
                            List<VectorEntry> batch = entries.subList(i, Math.min(i + batchSize, entries.size()));
                            if (!batch.isEmpty()) {
                                log.debug("Upserting batch {}/{} (size {})", 
                                    (i / batchSize) + 1, 
                                    (entries.size() + batchSize - 1) / batchSize, 
                                    batch.size());
                                batchFutures.add(vectorStoreClient.upsertEmbeddingsAsync(collectionName, batch));
                            }
                        }
                        
                        // Wait for all batches to complete
                        return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]));
                    });
            })
            .whenComplete((result, ex) -> {
                if (ex != null) {
                    log.error("Full indexing failed for path {}: {}", basePath, ex.getMessage(), ex);
                } else {
                    log.info("Full indexing completed successfully for path {}. Files: {}, Segments: {}, Entries Upserted: {}",
                        basePath, fileCount.get(), segmentCount.get(), entryCount.get());
                }
                // Reset indexing flag regardless of success or failure
                indexingInProgress.set(false);
            });
    }

    @Override
    public boolean isIndexingInProgress() {
        return indexingInProgress.get();
    }
    
    /**
     * Creates VectorEntry objects from code segments and their embeddings.
     * 
     * @param segments List of code segments
     * @param embeddings List of embeddings (must have same size as segments)
     * @return List of VectorEntry objects ready for storage
     */
    private List<VectorEntry> createVectorEntries(List<CodeSegment> segments, List<List<Float>> embeddings) {
        return IntStream.range(0, segments.size())
            .filter(i -> embeddings.get(i) != null && !embeddings.get(i).isEmpty())
            .mapToObj(i -> {
                CodeSegment segment = segments.get(i);
                return VectorEntry.builder()
                    .id(segment.getId() != null ? segment.getId() : UUID.randomUUID().toString())
                    .embedding(embeddings.get(i))
                    .metadata(Map.of(
                        "filePath", segment.getRelativeFilePath(),
                        "startLine", segment.getStartLine(),
                        "endLine", segment.getEndLine(),
                        "type", segment.getType().name(),
                        "entityName", segment.getEntityName() != null ? segment.getEntityName() : ""
                    ))
                    .document(segment.getContent())
                    .build();
            })
            .collect(Collectors.toList());
    }
} 
