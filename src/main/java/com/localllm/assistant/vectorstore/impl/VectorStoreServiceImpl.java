package com.localllm.assistant.vectorstore.impl;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.exception.VectorStoreException;
import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.parser.model.SegmentType;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.VectorStoreService;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import com.localllm.assistant.vectorstore.model.VectorSearchResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class VectorStoreServiceImpl implements VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreServiceImpl.class);

    private final VectorStoreClient vectorStoreClient;
    private final ChromaDBConfig chromaDBConfig;

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> storeSegmentAsync(CodeSegment segment, List<Float> embedding) {
        if (segment == null || embedding == null || embedding.isEmpty()) {
            log.error("Attempted to store null or empty segment/embedding.");
            return CompletableFuture.failedFuture(new IllegalArgumentException("Segment and embedding must not be null or empty"));
        }
        VectorEntry entry = mapSegmentToEntry(segment, embedding);
        log.debug("Service storing single segment: {}", entry.getId());
        return vectorStoreClient.ensureCollectionExists(chromaDBConfig.getDefaultCollectionName())
            .thenCompose(v -> vectorStoreClient.upsertEmbeddingsAsync(chromaDBConfig.getDefaultCollectionName(), List.of(entry)));
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> storeSegmentsAsync(List<CodeSegment> segments, List<List<Float>> embeddings) {
        if (segments == null || embeddings == null || segments.size() != embeddings.size()) {
            log.error("Mismatched segments ({}) and embeddings ({}) list sizes during store.",
                segments != null ? segments.size() : "null", embeddings != null ? embeddings.size() : "null");
            return CompletableFuture.failedFuture(new IllegalArgumentException("Segments and embeddings lists must match in size."));
        }
        List<VectorEntry> entries = mapSegmentsToEntries(segments, embeddings);
        if (entries.isEmpty()) {
            log.warn("No valid segment-embedding pairs found to store.");
            return CompletableFuture.completedFuture(null);
        }
        log.info("Service storing {} entries to collection '{}'", entries.size(), chromaDBConfig.getDefaultCollectionName());
        return vectorStoreClient.ensureCollectionExists(chromaDBConfig.getDefaultCollectionName())
            .thenCompose(v -> vectorStoreClient.upsertEmbeddingsAsync(chromaDBConfig.getDefaultCollectionName(), entries));
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<List<VectorSearchResult>> searchSimilarAsync(List<Float> queryEmbedding, int maxResults, double minScore) {
        if (queryEmbedding == null || queryEmbedding.isEmpty() || maxResults <= 0) {
            log.warn("Invalid search parameters: embedding empty={}, k={}, score={}",
                queryEmbedding == null || queryEmbedding.isEmpty(), maxResults, minScore);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        log.debug("Service searching collection '{}' with k={}, threshold={}", collectionName, maxResults, minScore);

        int fetchK = (int) (maxResults * 1.5) + 5;

        return vectorStoreClient.searchSimilarEmbeddingsAsync(collectionName, queryEmbedding, fetchK, null)
            .thenApply(entries -> entries.stream()
                // Use method reference 'this::mapEntryToSearchResult'
                .map(this::mapEntryToSearchResult)
                .filter(Objects::nonNull)
                // Use method reference 'VectorSearchResult::getScore'
                .filter(result -> result.getScore() >= minScore)
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList()))
            .exceptionally(ex -> {
                log.error("Vector search failed in service layer for collection '{}': {}", collectionName, ex.getMessage(), ex);
                return Collections.emptyList();
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Optional<CodeSegment>> getSegmentByIdAsync(String segmentId) {
        if (segmentId == null || segmentId.isBlank()) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        log.debug("Service getting segment by ID '{}' from collection '{}'", segmentId, collectionName);

        // IMPORTANT: Assumes 'id' is stored in metadata map with key "id"
        Map<String, Object> idFilter = Map.of("id", segmentId);

        // Use search with filter, k=1. No query embedding needed for metadata-only search if client supports it.
        // If client requires an embedding, pass a dummy one or handle differently.
        // Assuming client handles null embedding for metadata search:
        return vectorStoreClient.searchSimilarEmbeddingsAsync(collectionName, null, 1, idFilter)
            .thenApply(entries -> {
                if (entries.isEmpty()) {
                    log.debug("Segment ID '{}' not found via search in collection '{}'", segmentId, collectionName);
                    return Optional.<CodeSegment>empty();
                }
                if (entries.size() > 1) {
                    log.warn("Found multiple ({}) results for segment ID '{}' in collection '{}'. Returning first.", entries.size(), segmentId,
                        collectionName);
                }
                // Reconstruct segment from the first entry
                CodeSegment segment = reconstructCodeSegment(entries.get(0));
                return Optional.ofNullable(segment);
            })
            .exceptionally(ex -> {
                log.error("getSegmentById failed in service layer for ID '{}': {}", segmentId, ex.getMessage(), ex);
                // Propagate exception for clarity
                throw new VectorStoreException("getSegmentById failed in service layer", ex);
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Boolean> deleteSegmentAsync(String segmentId) {
        if (segmentId == null || segmentId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        log.info("Service deleting segment ID '{}' from collection '{}'", segmentId, collectionName);
        return vectorStoreClient.deleteEmbeddingsByIdsAsync(collectionName, List.of(segmentId))
            .thenApply(v -> true)
            .exceptionally(ex -> {
                log.error("Failed to delete segment {}: {}", segmentId, ex.getMessage(), ex);
                return false;
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Boolean> containsSegmentAsync(String segmentId) {
        if (segmentId == null || segmentId.isBlank()) {
            return CompletableFuture.completedFuture(false);
        }
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        log.debug("Service checking existence of segment ID '{}' in collection '{}'", segmentId, collectionName);
        // Use getSegmentById logic
        return getSegmentByIdAsync(segmentId) // Call the other service method
            .thenApply(Optional::isPresent) // True if Optional has a value
            .exceptionally(ex -> {
                log.error("containsSegmentAsync check failed exceptionally for ID '{}': {}", segmentId, ex.getMessage(), ex);
                return false;
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<List<VectorSearchResult>> findSimilarCodeSegments(
        List<Float> queryEmbedding,
        int maxResults,
        double minScore,
        Map<String, Object> filters) {

        if (queryEmbedding == null || queryEmbedding.isEmpty() || maxResults <= 0) {
            log.warn("Invalid search parameters for findSimilarCodeSegments: embedding empty={}, k={}, score={}",
                queryEmbedding == null || queryEmbedding.isEmpty(), maxResults, minScore);
            return CompletableFuture.completedFuture(Collections.emptyList());
        }

        String collectionName = chromaDBConfig.getDefaultCollectionName();
        log.debug("Service searching collection '{}' with filters, k={}, threshold={}",
            collectionName, maxResults, minScore);

        // Increase fetch size to account for filtering
        int fetchK = (int) (maxResults * 2) + 10;

        return vectorStoreClient.searchSimilarEmbeddingsAsync(collectionName, queryEmbedding, fetchK, filters)
            .thenApply(entries -> entries.stream()
                .map(this::mapEntryToSearchResult)
                .filter(Objects::nonNull)
                .filter(result -> result.getScore() >= minScore)
                .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                .limit(maxResults)
                .collect(Collectors.toList()))
            .exceptionally(ex -> {
                log.error("Vector search with filters failed in service layer: {}", ex.getMessage(), ex);
                return Collections.emptyList();
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Integer> deleteSegmentsByFilePathAsync(Path filePath) {
        if (filePath == null) {
            return CompletableFuture.completedFuture(0);
        }

        String collectionName = chromaDBConfig.getDefaultCollectionName();
        String relativeFilePath = filePath.toString().replace('\\', '/');

        log.info("Service deleting all segments for file path '{}' from collection '{}'",
            relativeFilePath, collectionName);

        // Use metadata filter for file path
        Map<String, Object> filePathFilter = Map.of("relativeFilePath", relativeFilePath);

        // First count how many segments will be deleted
        return vectorStoreClient.searchSimilarEmbeddingsAsync(collectionName, null, 1000, filePathFilter)
            .thenCompose(entries -> {
                int count = entries.size();
                if (count == 0) {
                    log.info("No segments found for file path '{}', skipping delete", relativeFilePath);
                    return CompletableFuture.completedFuture(0);
                }

                log.info("Deleting {} segments for file path '{}'", count, relativeFilePath);
                return vectorStoreClient.deleteEmbeddingsByMetadataAsync(collectionName, filePathFilter)
                    .thenApply(v -> count);
            })
            .exceptionally(ex -> {
                log.error("Failed to delete segments for file path '{}': {}",
                    relativeFilePath, ex.getMessage(), ex);
                return 0;
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Long> countSegmentsAsync() {
        String collectionName = chromaDBConfig.getDefaultCollectionName();
        log.debug("Service counting segments in collection '{}'", collectionName);

        return vectorStoreClient.countEmbeddingsAsync(collectionName)
            .exceptionally(ex -> {
                log.error("Failed to count segments in collection '{}': {}",
                    collectionName, ex.getMessage(), ex);
                return 0L;
            });
    }

    // --- Helper Methods ---

    private VectorEntry mapSegmentToEntry(CodeSegment segment, List<Float> embedding) {
        Map<String, Object> metadata = createMetadataMap(segment);
        return VectorEntry.builder()
            .id(segment.getId())
            .embedding(embedding)
            .metadata(metadata)
            .document(segment.getContent())
            .build();
    }

    private List<VectorEntry> mapSegmentsToEntries(List<CodeSegment> segments, List<List<Float>> embeddings) {
        List<VectorEntry> entries = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            CodeSegment segment = segments.get(i);
            List<Float> embedding = embeddings.get(i);
            if (segment != null && segment.getId() != null && !segment.getId().isBlank() &&
                embedding != null && !embedding.isEmpty()) {
                entries.add(mapSegmentToEntry(segment, embedding));
            } else {
                log.warn("Skipping invalid segment or null/empty embedding at index {}", i);
            }
        }
        return entries;
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

    /**
     * Helper to reconstruct a CodeSegment from a VectorEntry.
     * Assumes necessary data is stored in entry.document and entry.metadata.
     */
    private CodeSegment reconstructCodeSegment(VectorEntry entry) {
        try {
            Map<String, Object> meta = entry.getMetadata() != null ? entry.getMetadata() : Collections.emptyMap();

            String id = entry.getId();
            String content = entry.getDocument();
            String relativePath = (String) meta.get("relativeFilePath");
            int startLine = ((Number) meta.getOrDefault("startLine", 0)).intValue();
            int endLine = ((Number) meta.getOrDefault("endLine", 0)).intValue();
            String typeName = (String) meta.getOrDefault("type", SegmentType.UNKNOWN.name());
            String entityName = (String) meta.get("entityName");

            if (id == null || content == null || relativePath == null || startLine <= 0) {
                log.warn(
                    "Missing essential data in VectorEntry metadata/doc to reconstruct CodeSegment: id={}, path={}, startLine={}, content empty={}",
                    id, relativePath, startLine, content == null || content.isEmpty());
                return null;
            }

            return CodeSegment.builder()
                .id(id)
                .content(content)
                .relativeFilePath(relativePath)
                .startLine(startLine)
                .endLine(endLine)
                .type(SegmentType.valueOf(typeName))
                .entityName(entityName)
                .metadata(new HashMap<>(meta))
                .parentFqn((String) meta.get("parentFqn"))
                .parentId((String) meta.get("parentId"))
                .contentChecksum((String) meta.get("contentChecksum"))
                .build();
        } catch (Exception e) {
            log.error("Error reconstructing CodeSegment from VectorEntry id {}: {}", entry.getId(), e.getMessage(), e);
            return null;
        }
    }

    /**
     * Maps a vector entry to a search result, extracting segment and score information.
     */
    private VectorSearchResult mapEntryToSearchResult(VectorEntry entry) {
        if (entry == null) {
            return null;
        }

        Map<String, Object> metadata = entry.getMetadata();
        if (metadata == null) {
            metadata = Collections.emptyMap();
        }

        double score = calculateScore(metadata.get("_distance"));

        CodeSegment segment = reconstructCodeSegment(entry);
        if (segment == null) {
            log.warn("Could not reconstruct segment from entry {}", entry.getId());
            return null;
        }

        return VectorSearchResult.builder()
            .segment(segment)
            .score(score)
            .build();
    }

    private double calculateScore(Object distanceObj) {
        if (distanceObj instanceof Number) {
            double distance = ((Number) distanceObj).doubleValue();
            String distanceFunc = chromaDBConfig.getDistanceFunction();
            if ("l2".equalsIgnoreCase(distanceFunc)) {
                // L2 distance: Lower is better. Score = 1 / (1 + distance) maps [0, inf) to (0, 1]
                return 1.0 / (1.0 + distance);
            } else { // Assume cosine or ip
                // Cosine distance in ChromaDB is often 1 - similarity.
                // So, distance=0 means similarity=1. Score = 1 - distance.
                // Inner product (ip) distance might need different handling depending on normalization.
                // Assuming cosine distance for now:
                return 1.0 - distance;
            }
        }
        log.warn("Could not calculate score, _distance metadata missing or not a number: {}", distanceObj);
        return 0.0; // Default score
    }
}
