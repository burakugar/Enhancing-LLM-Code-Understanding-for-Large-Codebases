// --- File: /src/main/java/com/localllm/assistant/vectorstore/impl/ChromaDBClientImpl.java ---
package com.localllm.assistant.vectorstore.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.exception.VectorStoreException;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import jakarta.annotation.PostConstruct;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class ChromaDBClientImpl implements VectorStoreClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaDBClientImpl.class);
    private static final String API_V2_BASE = "/api/v2";
    private static final String DEFAULT_TENANT = "default_tenant";
    private static final String DEFAULT_DATABASE = "default_database";

    private final ChromaDBConfig chromaDBConfig;
    private final ObjectMapper objectMapper;
    private final CloseableHttpAsyncClient httpAsyncClient;
    private final Map<String, String> collectionNameToUuidCache = new ConcurrentHashMap<>();


    public ChromaDBClientImpl(ChromaDBConfig chromaDBConfig,
                              ObjectMapper objectMapper,
                              @Qualifier("sharedHttpAsyncClient") CloseableHttpAsyncClient sharedHttpAsyncClient) {
        this.chromaDBConfig = chromaDBConfig;
        this.objectMapper = objectMapper;
        this.httpAsyncClient = sharedHttpAsyncClient;
        log.info("ChromaDBClientImpl initialized with config: {} and shared HTTP client. API Base: {}", chromaDBConfig, API_V2_BASE);
    }

    @PostConstruct
    public void checkClientStatus() {
        if (this.httpAsyncClient == null) {
            log.error("CRITICAL: SharedHttpAsyncClient is null in ChromaDBClientImpl.");
        } else if (this.httpAsyncClient.getStatus() != IOReactorStatus.ACTIVE) {
            log.warn("SharedHttpAsyncClient is not active in ChromaDBClientImpl. Status: {}.", this.httpAsyncClient.getStatus());
        } else {
            log.info("ChromaDBClientImpl confirmed shared HTTP client is active.");
        }
    }

    public String getCollectionBasePath(String collectionIdOrName, boolean isUuid) {
        if (isUuid) {
            // Collection ID is already a UUID, use directly
            return String.format("%s/tenants/%s/databases/%s/collections/%s",
                API_V2_BASE, DEFAULT_TENANT, DEFAULT_DATABASE, collectionIdOrName);
        } else {
            // For listing all collections (or if name is provided and needs UUID resolution first)
            return String.format("%s/tenants/%s/databases/%s/collections",
                API_V2_BASE, DEFAULT_TENANT, DEFAULT_DATABASE);
        }
    }


    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> ensureCollectionExists(String collectionName) {
        long startTime = System.currentTimeMillis();
        log.debug("Ensuring collection exists: {}", collectionName);
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();

        getCollectionUuid(collectionName).thenAccept(uuid -> {
            log.info("Collection '{}' already exists with UUID {}. (Took {}ms)",
                collectionName, uuid, System.currentTimeMillis() - startTime);
            resultFuture.complete(null);
        }).exceptionally(ex -> {
            log.info("Collection '{}' does not exist or couldn't find UUID ({}). Attempting to create it.",
                collectionName, ex.getMessage());
            createActualCollection(collectionName, resultFuture, startTime);
            return null;
        });

        return resultFuture;
    }

    @Override
    public void clearCacheForCollection(String collectionName) {
        if (collectionName != null) {
            String removedUuid = collectionNameToUuidCache.remove(collectionName);
            if (removedUuid != null) {
                log.info("Cleared cached UUID '{}' for collection name '{}'", removedUuid, collectionName);
            } else {
                log.debug("No cached UUID found for collection name '{}' to clear.", collectionName);
            }
        }
    }

    private void createActualCollection(String collectionName, CompletableFuture<Void> result, long overallStartTime) {
        long operationStartTime = System.currentTimeMillis();
        try {
            String createCollectionUrlPath = getCollectionBasePath(null, false);
            String createCollectionFullUrl = chromaDBConfig.getUrl() + createCollectionUrlPath;

            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("name", collectionName);

            ObjectNode metadataNode = objectMapper.createObjectNode();
            if (chromaDBConfig.getDistanceFunction() != null && !chromaDBConfig.getDistanceFunction().isBlank()) {
                metadataNode.put("hnsw:space", chromaDBConfig.getDistanceFunction());
            }

            if (metadataNode.size() > 0) {
                requestBody.set("metadata", metadataNode);
            }

            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            log.info("Attempting to CREATE collection '{}' with URL: {} and body: {}", collectionName, createCollectionFullUrl, requestBodyJson);

            SimpleHttpRequest createRequest = SimpleRequestBuilder.post(createCollectionFullUrl)
                .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                .build();

            httpAsyncClient.execute(createRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    String responseBody = response.getBodyText();
                    log.debug("CREATE collection response (code {}): {}", response.getCode(), responseBody);
                    if (response.getCode() == 200 || response.getCode() == 201) { // 201 Created is also success
                        try {
                            JsonNode responseNode = objectMapper.readTree(responseBody);
                            if (responseNode.has("id")) {
                                String uuid = responseNode.get("id").asText();
                                collectionNameToUuidCache.put(collectionName, uuid);
                                log.info("Successfully created collection '{}' with UUID {}. (Operation took {}ms, Total ensure took {}ms)",
                                    collectionName, uuid, System.currentTimeMillis() - operationStartTime,
                                    System.currentTimeMillis() - overallStartTime);
                            } else {
                                log.warn("Created collection '{}' but response did not contain UUID. Response: {}",
                                    collectionName, responseBody);
                            }
                        } catch (Exception e) {
                            log.warn("Created collection '{}' but failed to extract UUID from response: {}",
                                collectionName, e.getMessage());
                        }
                        result.complete(null);
                    } else {
                        String error = "Failed to CREATE collection '" + collectionName + "': " + response.getCode() + " - " + responseBody;
                        log.error(error);
                        result.completeExceptionally(new VectorStoreException(error));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    log.error("HTTP request to CREATE collection '{}' failed: {}. URL: {}. (Operation took {}ms, Total ensure took {}ms)",
                        collectionName, ex.getMessage(), createCollectionFullUrl, System.currentTimeMillis() - operationStartTime,
                        System.currentTimeMillis() - overallStartTime, ex);
                    result.completeExceptionally(new VectorStoreException(
                        "Failed to CREATE collection (HTTP request failed)", ex));
                }

                @Override
                public void cancelled() {
                    log.warn("CREATE collection request cancelled. (Operation took {}ms, Total ensure took {}ms)",
                        System.currentTimeMillis() - operationStartTime, System.currentTimeMillis() - overallStartTime);
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error preparing CREATE collection request for '{}': {}", collectionName, e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error creating collection", e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> upsertEmbeddingsAsync(String collectionName, List<VectorEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            log.warn("No entries to upsert for collection: {}", collectionName);
            return CompletableFuture.completedFuture(null);
        }

        long startTime = System.currentTimeMillis();
        log.info("Upserting {} embeddings to collection '{}'", entries.size(), collectionName);
        CompletableFuture<Void> result = new CompletableFuture<>();

        getCollectionUuid(collectionName).thenAccept(collectionUuid -> {
            try {
                String addUrlPath = getCollectionBasePath(collectionUuid, true) + "/add";
                String addFullUrl = chromaDBConfig.getUrl() + addUrlPath;

                ObjectNode requestBody = objectMapper.createObjectNode();
                ArrayNode idsArray = objectMapper.createArrayNode();
                ArrayNode embeddingsArray = objectMapper.createArrayNode();
                ArrayNode metadatasArray = objectMapper.createArrayNode();
                ArrayNode documentsArray = objectMapper.createArrayNode();

                for (VectorEntry entry : entries) {
                    /* ids */
                    idsArray.add(entry.getId());

                    /* embeddings */
                    if (entry.getEmbedding() != null && !entry.getEmbedding().isEmpty()) {
                        ArrayNode emb = objectMapper.createArrayNode();
                        entry.getEmbedding().forEach(emb::add);
                        embeddingsArray.add(emb);
                    } else {
                        embeddingsArray.add(objectMapper.createArrayNode());
                    }

                    /* metadata – flatten complex objects to JSON strings */
                    if (entry.getMetadata() != null && !entry.getMetadata().isEmpty()) {
                        ObjectNode metaNode = objectMapper.createObjectNode();
                        for (Map.Entry<String, Object> m : entry.getMetadata().entrySet()) {
                            Object value = m.getValue();
                            if (value == null) {
                                metaNode.putNull(m.getKey());
                            } else if (value instanceof String s) {
                                metaNode.put(m.getKey(), s);
                            } else if (value instanceof Integer i) {
                                metaNode.put(m.getKey(), i);
                            } else if (value instanceof Long l) {
                                metaNode.put(m.getKey(), l);
                            } else if (value instanceof Double d) {
                                metaNode.put(m.getKey(), d);
                            } else if (value instanceof Float f) {
                                metaNode.put(m.getKey(), f);
                            } else if (value instanceof Boolean b) {
                                metaNode.put(m.getKey(), b);
                            } else {
                                /* Map / Collection / any other object -> store as JSON string */
                                metaNode.put(m.getKey(), objectMapper.writeValueAsString(value));
                            }
                        }
                        metadatasArray.add(metaNode);
                    } else {
                        metadatasArray.addNull();
                    }

                    /* document */
                    documentsArray.add(entry.getDocument() == null ? null : entry.getDocument());
                }

                requestBody.set("ids", idsArray);
                requestBody.set("embeddings", embeddingsArray);
                requestBody.set("metadatas", metadatasArray);
                requestBody.set("documents", documentsArray);

                String requestBodyJson = objectMapper.writeValueAsString(requestBody);

                if (log.isTraceEnabled() && entries.size() < 5) {
                    log.trace("Upsert body for '{}' (UUID: {}) -> {}: {}", collectionName, collectionUuid, addFullUrl, requestBodyJson);
                } else {
                    log.debug("Upsert {} embeddings to '{}' (UUID: {}). First id={}", entries.size(), collectionName,
                        collectionUuid, entries.get(0).getId());
                }

                SimpleHttpRequest upsertRequest = SimpleRequestBuilder.post(addFullUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();

                httpAsyncClient.execute(upsertRequest, new FutureCallback<>() {
                    @Override
                    public void completed(SimpleHttpResponse response) {
                        String responseBody = response.getBodyText();
                        if (response.getCode() == 200 || response.getCode() == 201) { // 201 Created is also success
                            log.info("Successfully upserted {} embeddings to '{}' (UUID: {}). ({} ms)",
                                entries.size(), collectionName, collectionUuid, System.currentTimeMillis() - startTime);
                            result.complete(null);
                        } else {
                            String msg = "Failed to upsert embeddings to '" + collectionName + "' (UUID: " + collectionUuid +
                                "): " + response.getCode() + " - " + responseBody;
                            log.error("{}. Request body (first 1000 chars): {}", msg,
                                requestBodyJson.substring(0, Math.min(1000, requestBodyJson.length())));
                            result.completeExceptionally(new VectorStoreException(msg));
                        }
                    }

                    @Override
                    public void failed(Exception ex) {
                        log.error("HTTP upsert to '{}' (UUID: {}) failed after {} ms: {}",
                            collectionName, collectionUuid, System.currentTimeMillis() - startTime, ex.getMessage(), ex);
                        result.completeExceptionally(new VectorStoreException(
                            "Failed to upsert embeddings (HTTP request failed)", ex));
                    }

                    @Override
                    public void cancelled() {
                        log.warn("Upsert to '{}' (UUID: {}) cancelled after {} ms",
                            collectionName, collectionUuid, System.currentTimeMillis() - startTime);
                        result.cancel(true);
                    }
                });

            } catch (Exception e) {
                log.error("Error building upsert request for '{}': {}", collectionName, e.getMessage(), e);
                result.completeExceptionally(new VectorStoreException("Error upserting embeddings", e));
            }
        }).exceptionally(ex -> {
            log.error("Failed to get UUID for collection '{}': {}", collectionName, ex.getMessage(), ex);
            result.completeExceptionally(
                new VectorStoreException("Failed to get collection UUID for upsert operation", ex));
            return null;
        });

        return result;
    }

    private CompletableFuture<String> getCollectionUuid(String collectionName) {
        String cachedUuid = collectionNameToUuidCache.get(collectionName);
        if (cachedUuid != null) {
            return CompletableFuture.completedFuture(cachedUuid);
        }

        CompletableFuture<String> resultFuture = new CompletableFuture<>();
        String listCollectionsUrlPath = getCollectionBasePath(null, false);
        String listCollectionsFullUrl = chromaDBConfig.getUrl() + listCollectionsUrlPath;

        SimpleHttpRequest getRequest = SimpleRequestBuilder.get(listCollectionsFullUrl).build();

        log.debug("Fetching collection UUID for '{}' from URL: {}", collectionName, listCollectionsFullUrl);

        httpAsyncClient.execute(getRequest, new FutureCallback<SimpleHttpResponse>() {
            @Override
            public void completed(SimpleHttpResponse response) {
                try {
                    String responseBody = response.getBodyText();
                    if (response.getCode() == 200) {
                        JsonNode rootNode = objectMapper.readTree(responseBody);
                        String uuid = null;

                        if (rootNode.isArray()) { // ChromaDB 0.4.x returns an array
                            for (JsonNode collection : rootNode) {
                                if (collection.has("name") && collection.get("name").asText().equals(collectionName)) {
                                    uuid = collection.get("id").asText();
                                    break;
                                }
                            }
                        } else if (rootNode.has("collections") && rootNode.get("collections").isArray()) { // ChromaDB 0.5.x / v2 API
                            for (JsonNode collection : rootNode.get("collections")) {
                                if (collection.has("name") && collection.get("name").asText().equals(collectionName)) {
                                    uuid = collection.get("id").asText();
                                    break;
                                }
                            }
                        }


                        if (uuid != null) {
                            collectionNameToUuidCache.put(collectionName, uuid);
                            log.debug("Found UUID '{}' for collection '{}'", uuid, collectionName);
                            resultFuture.complete(uuid);
                        } else {
                            resultFuture.completeExceptionally(
                                new VectorStoreException("Collection '" + collectionName + "' not found in list. Response: " + responseBody));
                        }
                    } else {
                        resultFuture.completeExceptionally(
                            new VectorStoreException("Failed to list collections: " + response.getCode() + " - " + responseBody));
                    }
                } catch (Exception e) {
                    resultFuture.completeExceptionally(
                        new VectorStoreException("Error processing collections list", e));
                }
            }

            @Override
            public void failed(Exception ex) {
                resultFuture.completeExceptionally(
                    new VectorStoreException("Failed to fetch collections list", ex));
            }

            @Override
            public void cancelled() {
                resultFuture.cancel(true);
            }
        });

        return resultFuture;
    }


    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<List<VectorEntry>> searchSimilarEmbeddingsAsync(String collectionName, List<Float> queryEmbedding, int k,
                                                                             Map<String, Object> metadataFilter) {
        long startTime = System.currentTimeMillis();
        log.debug("Searching for similar embeddings in collection '{}' with k={}, filter: {}", collectionName, k, metadataFilter);
        CompletableFuture<List<VectorEntry>> result = new CompletableFuture<>();

        getCollectionUuid(collectionName).thenAccept(collectionUuid -> {
            try {
                String queryUrlPath = getCollectionBasePath(collectionUuid, true) + "/query";
                String queryFullUrl = chromaDBConfig.getUrl() + queryUrlPath;

                ObjectNode requestBody = objectMapper.createObjectNode();

                if (queryEmbedding != null && !queryEmbedding.isEmpty()) {
                    ArrayNode queryEmbeddingsArrayNode = objectMapper.createArrayNode();
                    ArrayNode singleQueryEmbeddingNode = objectMapper.createArrayNode();
                    queryEmbedding.forEach(singleQueryEmbeddingNode::add);
                    queryEmbeddingsArrayNode.add(singleQueryEmbeddingNode);
                    requestBody.set("query_embeddings", queryEmbeddingsArrayNode);
                } else if (metadataFilter == null || metadataFilter.isEmpty()) {
                    log.warn("Querying collection '{}' (UUID: {}) without query_embeddings. If metadataFilter is also empty, this might be invalid.",
                        collectionName, collectionUuid);
                    if (metadataFilter == null || metadataFilter.isEmpty()) {
                        log.error(
                            "Querying collection '{}' (UUID: {}) without query_embeddings and without filter. This is invalid for /query endpoint.",
                            collectionName, collectionUuid);
                        result.completeExceptionally(new VectorStoreException("Query to /query endpoint requires query_embeddings or query_texts."));
                        return;
                    }
                    log.error(
                        "Cannot query collection '{}' (UUID: {}): queryEmbedding is null or empty, but /query endpoint requires 'query_embeddings' field.",
                        collectionName, collectionUuid);
                    result.completeExceptionally(new VectorStoreException("queryEmbedding cannot be null or empty for /query endpoint."));
                    return;
                }

                requestBody.put("n_results", k);

                if (metadataFilter != null && !metadataFilter.isEmpty()) {
                    requestBody.set("where", objectMapper.valueToTree(metadataFilter));
                }

                ArrayNode includeNode = objectMapper.createArrayNode()
                    .add("metadatas")
                    .add("documents")
                    .add("distances");
                requestBody.set("include", includeNode);

                String requestBodyJson = objectMapper.writeValueAsString(requestBody);
                log.debug("Query request body for collection '{}' (UUID: {}) to URL {}: {}", collectionName, collectionUuid, queryFullUrl,
                    requestBodyJson);

                SimpleHttpRequest queryRequest = SimpleRequestBuilder.post(queryFullUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();

                httpAsyncClient.execute(queryRequest, new FutureCallback<SimpleHttpResponse>() {
                    @Override
                    public void completed(SimpleHttpResponse response) {
                        try {
                            String responseBody = response.getBodyText();
                            log.debug("Query response (code {}): {}", response.getCode(), responseBody);
                            if (response.getCode() == 200) {
                                List<VectorEntry> entries = parseQueryResponseV2(responseBody);
                                log.info("Found {} similar embeddings in collection '{}' (UUID: {}). (Took {}ms)", entries.size(), collectionName,
                                    collectionUuid, System.currentTimeMillis() - startTime);
                                result.complete(entries);
                            } else {
                                String error =
                                    "Failed to query embeddings from '" + collectionName + "' (UUID: " + collectionUuid + "): " + response.getCode() +
                                        " - " + responseBody;
                                log.error(error);
                                result.completeExceptionally(new VectorStoreException(error));
                            }
                        } catch (Exception e) {
                            log.error("Error processing query response from collection '{}' (UUID: {}): {}", collectionName, collectionUuid,
                                e.getMessage(), e);
                            result.completeExceptionally(new VectorStoreException("Error processing query response", e));
                        }
                    }

                    @Override
                    public void failed(Exception ex) {
                        log.error("HTTP request to query embeddings from '{}' (UUID: {}) failed: {}. (Took {}ms)", collectionName, collectionUuid,
                            ex.getMessage(), System.currentTimeMillis() - startTime, ex);
                        result.completeExceptionally(new VectorStoreException("Failed to query embeddings (HTTP request failed)", ex));
                    }

                    @Override
                    public void cancelled() {
                        log.warn("Query embeddings request cancelled for collection '{}' (UUID: {}). (Took {}ms)", collectionName, collectionUuid,
                            System.currentTimeMillis() - startTime);
                        result.cancel(true);
                    }
                });
            } catch (Exception e) {
                log.error("Error preparing query request for collection '{}': {}", collectionName, e.getMessage(), e);
                result.completeExceptionally(new VectorStoreException("Error preparing query request", e));
            }
        }).exceptionally(ex -> {
            log.error("Failed to get UUID for collection '{}' for query: {}", collectionName, ex.getMessage(), ex);
            result.completeExceptionally(
                new VectorStoreException("Failed to get collection UUID for query operation", ex));
            return null;
        });
        return result;
    }

    private List<VectorEntry> parseQueryResponseV2(String responseJson) throws IOException {
        JsonNode rootNode = objectMapper.readTree(responseJson);
        List<VectorEntry> results = new ArrayList<>();

        JsonNode idsListOfLists = rootNode.path("ids");
        JsonNode distancesListOfLists = rootNode.path("distances");
        JsonNode metadatasListOfLists = rootNode.path("metadatas");
        JsonNode documentsListOfLists = rootNode.path("documents");
        JsonNode embeddingsListOfLists = rootNode.path("embeddings");


        if (idsListOfLists.isMissingNode() || !idsListOfLists.isArray() || idsListOfLists.isEmpty()) {
            log.warn("Query response missing 'ids' or 'ids' is not a non-empty array of lists. Response: {}", responseJson);
            return results;
        }

        JsonNode idsForQuery = idsListOfLists.get(0);
        JsonNode distancesForQuery = distancesListOfLists.path(0);
        JsonNode metadatasForQuery = metadatasListOfLists.path(0);
        JsonNode documentsForQuery = documentsListOfLists.path(0);
        JsonNode embeddingsForQuery = embeddingsListOfLists.path(0);


        if (idsForQuery == null || !idsForQuery.isArray()) {
            log.warn("Query response 'ids[0]' is null or not an array. Response: {}", responseJson);
            return results;
        }

        for (int i = 0; i < idsForQuery.size(); i++) {
            String id = idsForQuery.get(i).asText();

            Map<String, Object> metadata = new HashMap<>();
            if (metadatasForQuery != null && !metadatasForQuery.isMissingNode() && i < metadatasForQuery.size() &&
                metadatasForQuery.get(i) != null && !metadatasForQuery.get(i).isNull()) {
                try {
                    metadata = objectMapper.convertValue(metadatasForQuery.get(i), Map.class);
                } catch (IllegalArgumentException e) {
                    log.warn("Could not convert metadata for id {}: {}. Metadata node: {}", id, e.getMessage(), metadatasForQuery.get(i).toString());
                }
            }

            if (distancesForQuery != null && !distancesForQuery.isMissingNode() && i < distancesForQuery.size() &&
                distancesForQuery.get(i) != null && !distancesForQuery.get(i).isNull()) {
                metadata.put("_distance", distancesForQuery.get(i).asDouble());
            }

            String document = null;
            if (documentsForQuery != null && !documentsForQuery.isMissingNode() && i < documentsForQuery.size() &&
                documentsForQuery.get(i) != null && !documentsForQuery.get(i).isNull()) {
                document = documentsForQuery.get(i).asText();
            }

            List<Float> embedding = null;
            if (embeddingsForQuery != null && !embeddingsForQuery.isMissingNode() && i < embeddingsForQuery.size() &&
                embeddingsForQuery.get(i) != null && embeddingsForQuery.get(i).isArray()) {
                embedding = new ArrayList<>();
                for (JsonNode val : embeddingsForQuery.get(i)) {
                    embedding.add(val.floatValue());
                }
            }


            VectorEntry entry = VectorEntry.builder()
                .id(id)
                .embedding(embedding)
                .metadata(metadata)
                .document(document)
                .build();
            results.add(entry);
        }
        return results;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> deleteEmbeddingsByMetadataAsync(String collectionName, Map<String, Object> metadataFilter) {
        if (metadataFilter == null || metadataFilter.isEmpty()) {
            log.error("Metadata filter cannot be null or empty for delete operation on collection '{}' for safety.", collectionName);
            return CompletableFuture.failedFuture(new VectorStoreException("Metadata filter cannot be null or empty for safety"));
        }
        long startTime = System.currentTimeMillis();
        log.info("Deleting embeddings by metadata filter from collection '{}': {}", collectionName, metadataFilter);
        CompletableFuture<Void> result = new CompletableFuture<>();

        getCollectionUuid(collectionName).thenAccept(collectionUuid -> {
            try {
                String deleteUrlPath = getCollectionBasePath(collectionUuid, true) + "/delete";
                String deleteFullUrl = chromaDBConfig.getUrl() + deleteUrlPath;

                ObjectNode requestBody = objectMapper.createObjectNode();
                requestBody.set("where", objectMapper.valueToTree(metadataFilter));

                String requestBodyJson = objectMapper.writeValueAsString(requestBody);
                log.debug("Delete by metadata request body for collection '{}' (UUID: {}) to URL {}: {}", collectionName, collectionUuid,
                    deleteFullUrl, requestBodyJson);

                SimpleHttpRequest deleteRequest = SimpleRequestBuilder.post(deleteFullUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();

                httpAsyncClient.execute(deleteRequest, new FutureCallback<SimpleHttpResponse>() {
                    @Override
                    public void completed(SimpleHttpResponse response) {
                        String responseBody = response.getBodyText();
                        if (response.getCode() == 200) {
                            log.info(
                                "Successfully initiated delete embeddings from collection '{}' (UUID: {}) using metadata filter. Response: {}. (Took {}ms)",
                                collectionName, collectionUuid, responseBody, System.currentTimeMillis() - startTime);
                            result.complete(null);
                        } else {
                            String error =
                                "Failed to delete embeddings from '" + collectionName + "' (UUID: " + collectionUuid + "): " + response.getCode() +
                                    " - " + responseBody;
                            log.error(error);
                            result.completeExceptionally(new VectorStoreException(error));
                        }
                    }

                    @Override
                    public void failed(Exception ex) {
                        log.error("HTTP request to delete embeddings from '{}' (UUID: {}) failed: {}. (Took {}ms)", collectionName, collectionUuid,
                            ex.getMessage(), System.currentTimeMillis() - startTime, ex);
                        result.completeExceptionally(new VectorStoreException("Failed to delete embeddings (HTTP request failed)", ex));
                    }

                    @Override
                    public void cancelled() {
                        log.warn("Delete embeddings request cancelled for collection '{}' (UUID: {}). (Took {}ms)", collectionName, collectionUuid,
                            System.currentTimeMillis() - startTime);
                        result.cancel(true);
                    }
                });
            } catch (Exception e) {
                log.error("Error preparing delete embeddings by metadata request for collection '{}': {}", collectionName, e.getMessage(), e);
                result.completeExceptionally(new VectorStoreException("Error deleting embeddings by metadata", e));
            }
        }).exceptionally(ex -> {
            log.error("Failed to get UUID for collection '{}' for delete by metadata: {}", collectionName, ex.getMessage(), ex);
            result.completeExceptionally(
                new VectorStoreException("Failed to get collection UUID for delete by metadata operation", ex));
            return null;
        });
        return result;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> deleteEmbeddingsByIdsAsync(String collectionName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("No IDs provided for deletion from collection '{}'", collectionName);
            return CompletableFuture.completedFuture(null);
        }
        long startTime = System.currentTimeMillis();
        log.info("Deleting {} embeddings by IDs from collection '{}'", ids.size(), collectionName);
        CompletableFuture<Void> result = new CompletableFuture<>();

        getCollectionUuid(collectionName).thenAccept(collectionUuid -> {
            try {
                String deleteUrlPath = getCollectionBasePath(collectionUuid, true) + "/delete"; // Use UUID
                String deleteFullUrl = chromaDBConfig.getUrl() + deleteUrlPath;

                ObjectNode requestBody = objectMapper.createObjectNode();
                ArrayNode idsNode = objectMapper.createArrayNode();
                ids.forEach(idsNode::add);
                requestBody.set("ids", idsNode);

                String requestBodyJson = objectMapper.writeValueAsString(requestBody);
                log.debug("Delete by IDs request body for collection '{}' (UUID: {}) to URL {}: {}", collectionName, collectionUuid, deleteFullUrl,
                    requestBodyJson);

                SimpleHttpRequest deleteRequest = SimpleRequestBuilder.post(deleteFullUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();

                httpAsyncClient.execute(deleteRequest, new FutureCallback<SimpleHttpResponse>() {
                    @Override
                    public void completed(SimpleHttpResponse response) {
                        String responseBody = response.getBodyText();
                        if (response.getCode() == 200) {
                            log.info(
                                "Successfully initiated delete of {} embeddings by ID from collection '{}' (UUID: {}). Response: {}. (Took {}ms)",
                                ids.size(), collectionName, collectionUuid, responseBody, System.currentTimeMillis() - startTime);
                            result.complete(null);
                        } else {
                            String error = "Failed to delete embeddings by ID from '" + collectionName + "' (UUID: " + collectionUuid + "): " +
                                response.getCode() + " - " + responseBody;
                            log.error(error);
                            result.completeExceptionally(new VectorStoreException(error));
                        }
                    }

                    @Override
                    public void failed(Exception ex) {
                        log.error("HTTP request to delete embeddings by ID from '{}' (UUID: {}) failed: {}. (Took {}ms)", collectionName,
                            collectionUuid, ex.getMessage(), System.currentTimeMillis() - startTime, ex);
                        result.completeExceptionally(new VectorStoreException("Failed to delete embeddings by ID (HTTP request failed)", ex));
                    }

                    @Override
                    public void cancelled() {
                        log.warn("Delete embeddings by ID request cancelled for collection '{}' (UUID: {}). (Took {}ms)", collectionName,
                            collectionUuid, System.currentTimeMillis() - startTime);
                        result.cancel(true);
                    }
                });
            } catch (Exception e) {
                log.error("Error preparing delete embeddings by ID request for collection '{}': {}", collectionName, e.getMessage(), e);
                result.completeExceptionally(new VectorStoreException("Error deleting embeddings by ID", e));
            }
        }).exceptionally(ex -> {
            log.error("Failed to get UUID for collection '{}' for delete by IDs: {}", collectionName, ex.getMessage(), ex);
            result.completeExceptionally(
                new VectorStoreException("Failed to get collection UUID for delete by IDs operation", ex));
            return null;
        });
        return result;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Long> countEmbeddingsAsync(String collectionName) {
        long startTime = System.currentTimeMillis();
        log.debug("Counting embeddings in collection '{}'", collectionName);
        CompletableFuture<Long> result = new CompletableFuture<>();

        getCollectionUuid(collectionName).thenAccept(collectionUuid -> {
            try {
                String countUrlPath = getCollectionBasePath(collectionUuid, true) + "/count"; // Use UUID
                String countFullUrl = chromaDBConfig.getUrl() + countUrlPath;

                SimpleHttpRequest countRequest = SimpleRequestBuilder.post(countFullUrl).build(); // POST for /count

                log.debug("Attempting to count collection items from URL: {}", countFullUrl);
                httpAsyncClient.execute(countRequest, new FutureCallback<SimpleHttpResponse>() {
                    @Override
                    public void completed(SimpleHttpResponse response) {
                        try {
                            String responseBody = response.getBodyText();
                            log.debug("Count collection items response (code {}): {}", response.getCode(), responseBody);
                            if (response.getCode() == 200) {
                                long count = Long.parseLong(responseBody.trim());
                                log.info("Collection '{}' (UUID: {}) contains {} embeddings. (Took {}ms)", collectionName, collectionUuid, count,
                                    System.currentTimeMillis() - startTime);
                                result.complete(count);
                            } else {
                                String error = "Failed to count collection items from '" + collectionName + "' (UUID: " + collectionUuid + "): " +
                                    response.getCode() + " - " + responseBody;
                                log.error(error);
                                result.completeExceptionally(new VectorStoreException(error));
                            }
                        } catch (Exception e) {
                            log.error("Error processing count collection items response for '{}' (UUID: {}): {}", collectionName, collectionUuid,
                                e.getMessage(), e);
                            result.completeExceptionally(new VectorStoreException("Error processing count response", e));
                        }
                    }

                    @Override
                    public void failed(Exception ex) {
                        log.error("HTTP request to count collection items from '{}' (UUID: {}) failed: {}. (Took {}ms)", collectionName,
                            collectionUuid, ex.getMessage(), System.currentTimeMillis() - startTime, ex);
                        result.completeExceptionally(new VectorStoreException("Failed to count embeddings (HTTP request failed)", ex));
                    }

                    @Override
                    public void cancelled() {
                        log.warn("Count collection items request cancelled for collection '{}' (UUID: {}). (Took {}ms)", collectionName,
                            collectionUuid, System.currentTimeMillis() - startTime);
                        result.cancel(true);
                    }
                });
            } catch (Exception e) {
                log.error("Error preparing count collection items request for '{}': {}", collectionName, e.getMessage(), e);
                result.completeExceptionally(new VectorStoreException("Error counting embeddings", e));
            }
        }).exceptionally(ex -> {
            log.error("Failed to get UUID for collection '{}' for count: {}", collectionName, ex.getMessage(), ex);
            result.completeExceptionally(
                new VectorStoreException("Failed to get collection UUID for count operation", ex));
            return null;
        });
        return result;
    }
}
