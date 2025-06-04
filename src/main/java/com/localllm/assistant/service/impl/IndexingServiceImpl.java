// --- File: /main/java/com/localllm/assistant/service/impl/IndexingServiceImpl.java ---
package com.localllm.assistant.service.impl;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.exception.IndexingException;
import com.localllm.assistant.parser.ParserService;
import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.service.FileMonitorService;
import com.localllm.assistant.service.IndexingService;
import com.localllm.assistant.util.FileUtils;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

@Service
@RequiredArgsConstructor
public class IndexingServiceImpl implements IndexingService {

    private static final Logger log = LoggerFactory.getLogger(IndexingServiceImpl.class);

    private final ParserService parserService;
    private final EmbeddingService embeddingService;
    private final VectorStoreClient vectorStoreClient;
    private final ChromaDBConfig chromaDBConfig;
    private final FileMonitorService fileMonitorService;

    private final AtomicBoolean indexingInProgress = new AtomicBoolean(false);
    private final AtomicInteger totalFilesToProcessCounter = new AtomicInteger(0);
    private final AtomicInteger totalSegmentsToProcessCounter = new AtomicInteger(0);
    private final AtomicInteger totalEntriesToUpsertCounter = new AtomicInteger(0);
    private final AtomicInteger entriesSuccessfullyUpsertedCounter = new AtomicInteger(0);

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_ORCHESTRATION)
    public CompletableFuture<Void> startIndexing(Path basePath) {
        long overallStartTime = System.currentTimeMillis();
        log.info("startIndexing called for path: {}", basePath);

        totalFilesToProcessCounter.set(0);
        totalSegmentsToProcessCounter.set(0);
        totalEntriesToUpsertCounter.set(0);
        entriesSuccessfullyUpsertedCounter.set(0);

        log.debug("Attempting to set/restart file monitor for path: {}", basePath.toAbsolutePath().normalize());
        try {
            fileMonitorService.setMonitoredPathAndRestart(basePath.toAbsolutePath().normalize());
            log.info("File monitor successfully (re)started for path: {}", basePath);
        } catch (Exception e) {
            log.error("Failed to set/restart file monitor for path {}: {}. Indexing will proceed, but file monitoring might be impacted.", basePath,
                e.getMessage(), e);
        }

        if (!Files.isDirectory(basePath)) {
            log.error("Provided path is not a directory: {}", basePath);
            return CompletableFuture.failedFuture(
                new IndexingException("Provided path is not a directory: " + basePath));
        }
        log.debug("Validated path is a directory: {}", basePath);

        if (!indexingInProgress.compareAndSet(false, true)) {
            log.warn("Indexing requested for {} but already in progress. Request ignored.", basePath);
            return CompletableFuture.failedFuture(
                new IndexingException("Indexing is already in progress."));
        }
        log.info("Indexing lock acquired. Starting full indexing process for: {}", basePath);

        String collectionName = chromaDBConfig.getDefaultCollectionName();
        int batchSize = chromaDBConfig.getBatchSize();
        log.info("Using ChromaDB collection: '{}', Batch size for upserts: {}", collectionName, batchSize);

        CompletableFuture<Void> overallIndexingFuture = new CompletableFuture<>();
        // ADD THIS LINE to clear cache before ensuring collection exists
        vectorStoreClient.clearCacheForCollection(collectionName);
        log.info("Cleared vector store client cache for collection '{}' before starting indexing.", collectionName);
        long stepStartTime = System.currentTimeMillis();
        log.info("[Indexing Step 1/6] Ensuring collection '{}' exists...", collectionName);
        vectorStoreClient.ensureCollectionExists(collectionName)
            .thenCompose(v -> {
                log.info("[Indexing Step 1/6] Collection '{}' ensured. (Took {}ms)", collectionName, System.currentTimeMillis() - stepStartTime);
                long findFilesStartTime = System.currentTimeMillis();
                log.info("[Indexing Step 2/6] Finding Java files in path: {}", basePath);
                try {
                    List<Path> javaFiles = FileUtils.findAllJavaFiles(basePath);
                    totalFilesToProcessCounter.set(javaFiles.size());
                    log.info("[Indexing Step 2/6] Found {} Java files to index in {}. (Took {}ms)", javaFiles.size(), basePath,
                        System.currentTimeMillis() - findFilesStartTime);

                    if (javaFiles.isEmpty()) {
                        log.warn("No Java files found in the directory: {}. Skipping parsing and embedding.", basePath);
                        return CompletableFuture.completedFuture(Collections.<CodeSegment>emptyList());
                    }
                    if (log.isDebugEnabled()) {
                        log.debug("First {} files to be indexed (relative to base path {}):", Math.min(5, javaFiles.size()), basePath);
                        javaFiles.stream().limit(5).forEach(javaFile -> log.debug("  - {}", basePath.relativize(javaFile)));
                        if (javaFiles.size() > 5) {
                            log.debug("  ... and {} more files.", javaFiles.size() - 5);
                        }
                    }

                    long parseFilesStartTime = System.currentTimeMillis();
                    log.info("[Indexing Step 3/6] Parsing {} files...", javaFiles.size());
                    return parserService.parseFilesAsync(javaFiles, basePath)
                        .whenComplete((parsedSegmentsResult, ex) -> {
                            if (ex != null) {
                                log.error("[Indexing Step 3/6] File parsing failed after {}ms.", System.currentTimeMillis() - parseFilesStartTime,
                                    ex);
                            } else {
                                log.info("[Indexing Step 3/6] File parsing completed. (Took {}ms)", System.currentTimeMillis() - parseFilesStartTime);
                            }
                        });
                } catch (IOException e) {
                    log.error("[Indexing Step 2/6] Failed to find source files in {}: {}", basePath, e.getMessage(), e);
                    throw new IndexingException("Failed to find source files in " + basePath, e);
                }
            })
            .thenCompose(parsedSegments -> {
                if (parsedSegments == null) {
                    log.error("Parser service returned null segments list. This is unexpected.");
                    throw new IndexingException("Parser service returned null segments list.");
                }
                totalSegmentsToProcessCounter.set(parsedSegments.size());
                log.info("Successfully parsed {} files into {} code segments.", totalFilesToProcessCounter.get(), parsedSegments.size());

                if (parsedSegments.isEmpty()) {
                    log.info("No code segments extracted from parsing. Skipping embedding.");
                    return CompletableFuture.completedFuture(Map.entry(Collections.<CodeSegment>emptyList(), Collections.<List<Float>>emptyList()));
                }

                long embedStartTime = System.currentTimeMillis();
                log.info("[Indexing Step 4/6] Generating embeddings for {} segments...", parsedSegments.size());
                return embeddingService.generateEmbeddingsAsync(parsedSegments)
                    .thenApply(embeddings -> {
                        log.info("[Indexing Step 4/6] Embedding generation completed. (Took {}ms)", System.currentTimeMillis() - embedStartTime);
                        if (embeddings == null) {
                            log.error("Embedding service returned null for embeddings list.");
                            throw new IndexingException("Embedding service returned null for embeddings list.");
                        }
                        if (embeddings.size() != parsedSegments.size()) {
                            log.warn(
                                "CRITICAL MISMATCH: Embedding service returned {} embedding lists for {} input segments. This indicates a bug or partial failure in EmbeddingServiceImpl's result alignment. Proceeding with available data.",
                                embeddings.size(), parsedSegments.size());
                        }
                        log.info("Received {} embedding lists from service (should match segment count).", embeddings.size());
                        return Map.entry(parsedSegments, embeddings);
                    });
            })
            .thenCompose((Map.Entry<List<CodeSegment>, List<List<Float>>> segmentsAndEmbeddingsEntry) -> {
                List<CodeSegment> allParsedSegments = segmentsAndEmbeddingsEntry.getKey();
                List<List<Float>> allEmbeddings = segmentsAndEmbeddingsEntry.getValue();

                List<CodeSegment> successfullyEmbeddedSegments = new ArrayList<>();
                List<List<Float>> correspondingValidEmbeddings = new ArrayList<>();

                int MismatchedLogThreshold = 5;
                int mismatchCount = 0;

                for (int i = 0; i < allParsedSegments.size(); i++) {
                    CodeSegment segment = allParsedSegments.get(i);
                    if (i < allEmbeddings.size()) {
                        List<Float> currentEmbedding = allEmbeddings.get(i);
                        if (currentEmbedding != null && !currentEmbedding.isEmpty()) {
                            successfullyEmbeddedSegments.add(segment);
                            correspondingValidEmbeddings.add(currentEmbedding);
                        } else {
                            if (mismatchCount < MismatchedLogThreshold) {
                                log.warn("Segment ID '{}' (Path: {}, Lines: {}-{}) was skipped or failed embedding (embedding list is null/empty).",
                                    segment.getId(), segment.getRelativeFilePath(), segment.getStartLine(), segment.getEndLine());
                            }
                            mismatchCount++;
                        }
                    } else {
                        if (mismatchCount < MismatchedLogThreshold) {
                            log.warn("No corresponding embedding found for segment ID '{}' (index {}). Skipping.", segment.getId(), i);
                        }
                        mismatchCount++;
                    }
                }
                if (mismatchCount > MismatchedLogThreshold) {
                    log.warn("...and {} more segments were skipped or failed embedding.", mismatchCount - MismatchedLogThreshold);
                }


                long createEntriesStartTime = System.currentTimeMillis();
                log.info("[Indexing Step 5/6] Creating vector entries from {} successfully embedded segments...",
                    successfullyEmbeddedSegments.size());
                List<VectorEntry> entriesToUpsert = createVectorEntries(successfullyEmbeddedSegments, correspondingValidEmbeddings);
                totalEntriesToUpsertCounter.set(entriesToUpsert.size());
                log.info("[Indexing Step 5/6] Created {} valid vector entries. (Took {}ms)",
                    entriesToUpsert.size(), System.currentTimeMillis() - createEntriesStartTime);

                entriesSuccessfullyUpsertedCounter.set(0);

                if (entriesToUpsert.isEmpty()) {
                    log.warn("No valid vector entries created after embedding. Skipping upsert.");
                    return CompletableFuture.completedFuture(null);
                }

                long upsertStartTime = System.currentTimeMillis();
                log.info("[Indexing Step 6/6] Upserting {} entries in batches to collection '{}' (batch size: {})...", entriesToUpsert.size(),
                    collectionName, batchSize);

                List<CompletableFuture<Void>> batchFutures = new ArrayList<>();
                int numBatches = (entriesToUpsert.size() + batchSize - 1) / batchSize;

                for (int i = 0; i < entriesToUpsert.size(); i += batchSize) {
                    List<VectorEntry> currentBatch = entriesToUpsert.subList(i, Math.min(i + batchSize, entriesToUpsert.size()));
                    if (!currentBatch.isEmpty()) {
                        int currentBatchNum = (i / batchSize) + 1;
                        log.info("Upserting batch {}/{} (size {}) to collection '{}'",
                            currentBatchNum, numBatches, currentBatch.size(), collectionName);

                        batchFutures.add(vectorStoreClient.upsertEmbeddingsAsync(collectionName, currentBatch)
                            .thenRun(() -> {
                                int batchCount = currentBatch.size();
                                entriesSuccessfullyUpsertedCounter.addAndGet(batchCount);
                                log.info("Batch {}/{} ({} entries) upsert completed. Total entries upserted so far: {}/{}",
                                    currentBatchNum, numBatches, batchCount, entriesSuccessfullyUpsertedCounter.get(), entriesToUpsert.size());
                            })
                            .exceptionally(ex -> {
                                log.error("Failed to upsert batch {}/{}: {}", currentBatchNum, numBatches, ex.getMessage(), ex);
                                return null;
                            }));
                    }
                }

                return CompletableFuture.allOf(batchFutures.toArray(new CompletableFuture[0]))
                    .whenComplete((res, ex) -> {
                        if (ex != null) {
                            log.error("[Indexing Step 6/6] One or more batch upsert operations failed after {}ms.",
                                System.currentTimeMillis() - upsertStartTime, ex);
                        } else {
                            log.info(
                                "[Indexing Step 6/6] All {} batch upsert operations initiated. Total entries successfully upserted (based on counters): {}. (Took {}ms for submitting all batches)",
                                batchFutures.size(), entriesSuccessfullyUpsertedCounter.get(), System.currentTimeMillis() - upsertStartTime);
                        }
                    });
            })
            .thenRun(() -> {
                log.info("Full indexing process completed successfully for path '{}'. Total time: {}ms",
                    basePath, System.currentTimeMillis() - overallStartTime);
                log.info("Summary: Files found: {}, Segments parsed: {}, Entries created: {}, Entries upserted: {}",
                    totalFilesToProcessCounter.get(), totalSegmentsToProcessCounter.get(), totalEntriesToUpsertCounter.get(),
                    entriesSuccessfullyUpsertedCounter.get());
                overallIndexingFuture.complete(null);
            })
            .exceptionally(ex -> {
                Throwable cause = ex;
                if (ex instanceof java.util.concurrent.CompletionException && ex.getCause() != null) {
                    cause = ex.getCause();
                }
                log.error("Full indexing pipeline failed for path '{}': {}. Total time: {}ms",
                    basePath, cause.getMessage(), System.currentTimeMillis() - overallStartTime, cause);
                log.error("Partial Summary: Files found: {}, Segments parsed: {}, Entries created: {}, Entries upserted: {}",
                    totalFilesToProcessCounter.get(), totalSegmentsToProcessCounter.get(), totalEntriesToUpsertCounter.get(),
                    entriesSuccessfullyUpsertedCounter.get());
                overallIndexingFuture.completeExceptionally(cause);
                return null;
            })
            .whenComplete((res, ex) -> {
                log.debug("Releasing indexing lock for path: {}", basePath);
                indexingInProgress.set(false);
            });

        return overallIndexingFuture;
    }

    @Override
    public boolean isIndexingInProgress() {
        boolean inProgress = indexingInProgress.get();
        log.trace("isIndexingInProgress called, returning: {}", inProgress);
        return inProgress;
    }

    public double getIndexingProgress() {
        if (!indexingInProgress.get()) {
            if (totalFilesToProcessCounter.get() == 0 && totalSegmentsToProcessCounter.get() == 0 && entriesSuccessfullyUpsertedCounter.get() == 0) {
                return 0.0;
            }
            return 1.0;
        }
        if (totalEntriesToUpsertCounter.get() == 0) {
            if (totalSegmentsToProcessCounter.get() > 0) {
                return 0.5;
            }
            if (totalFilesToProcessCounter.get() > 0) {
                return 0.2;
            }
            return 0.05;
        }
        return (double) entriesSuccessfullyUpsertedCounter.get() / totalEntriesToUpsertCounter.get();
    }

    private List<VectorEntry> createVectorEntries(List<CodeSegment> segments, List<List<Float>> embeddings) {
        log.debug("Entering createVectorEntries with {} segments and {} embedding lists.", segments.size(), embeddings.size());
        if (segments.size() != embeddings.size()) {
            log.error(
                "CRITICAL MISMATCH in createVectorEntries: Segment count ({}) and embedding list count ({}) differ. This will lead to incorrect data. Returning empty list.",
                segments.size(), embeddings.size());
            return Collections.emptyList();
        }

        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            CodeSegment segment = segments.get(i);
            List<Float> embedding = embeddings.get(i);

            if (embedding == null || embedding.isEmpty()) {
                log.warn(
                    "Segment ID '{}' (Path: {}, Lines: {}-{}) has null or empty embedding at createVectorEntries stage. This should have been filtered. Skipping.",
                    segment.getId(), segment.getRelativeFilePath(), segment.getStartLine(), segment.getEndLine());
                continue;
            }

            String entryId = segment.getId();
            if (entryId == null || entryId.isBlank()) {
                log.warn("Segment from path {} (lines {}-{}) had a null or blank ID. Generating UUID for VectorEntry.",
                    segment.getRelativeFilePath(), segment.getStartLine(), segment.getEndLine());
                entryId = UUID.randomUUID().toString();
            }

            Map<String, Object> metadata = new HashMap<>();
            // CORRECTED LINE: Use "relativeFilePath" consistently
            metadata.put("relativeFilePath", segment.getRelativeFilePath());
            metadata.put("startLine", segment.getStartLine());
            metadata.put("endLine", segment.getEndLine());
            metadata.put("type", segment.getType().name());
            if (segment.getEntityName() != null && !segment.getEntityName().isBlank()) {
                metadata.put("entityName", segment.getEntityName());
            }
            metadata.put("id", entryId);

            if (segment.getMetadata() != null) {
                segment.getMetadata().forEach((key, value) -> {
                    if (!metadata.containsKey(key) && value != null) {
                        metadata.put(key, value);
                    }
                });
            }

            entries.add(VectorEntry.builder()
                .id(entryId)
                .embedding(embedding)
                .metadata(metadata)
                .document(segment.getContent())
                .build());
        }
        log.debug("Exiting createVectorEntries, created {} entries.", entries.size());
        return entries;
    }
}
