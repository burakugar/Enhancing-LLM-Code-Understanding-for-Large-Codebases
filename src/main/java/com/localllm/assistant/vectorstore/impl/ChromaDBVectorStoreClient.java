package com.localllm.assistant.vectorstore.impl;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.exception.VectorStoreException;
import com.localllm.assistant.vectorstore.VectorStoreClient;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of VectorStoreClient for interacting with ChromaDB via its HTTP API.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ChromaDBVectorStoreClient implements VectorStoreClient {

    private final ChromaDBConfig chromaDBConfig;
    private final ObjectMapper objectMapper;
    private final CloseableHttpAsyncClient httpClient;

    private static final String API_BASE_PATH = "/api/v1";
    private static final String COLLECTIONS_ENDPOINT = API_BASE_PATH + "/collections";

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> ensureCollectionExists(String collectionName) {
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();

        try {
            // First check if collection exists
            URI listCollectionsUri = buildChromaUri(COLLECTIONS_ENDPOINT);
            SimpleHttpRequest request = SimpleRequestBuilder.get(listCollectionsUri).build();

            httpClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() != 200) {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "Failed to retrieve collections list from ChromaDB. Status: " + response.getCode()));
                        return;
                    }

                    try {
                        JsonNode jsonResponse = objectMapper.readTree(response.getBodyText());
                        boolean exists = false;

                        if (jsonResponse.has("collections") && jsonResponse.get("collections").isArray()) {
                            JsonNode collections = jsonResponse.get("collections");
                            for (JsonNode collection : collections) {
                                if (collection.has("name") && 
                                    collection.get("name").asText().equals(collectionName)) {
                                    exists = true;
                                    break;
                                }
                            }
                        }

                        if (exists) {
                            log.info("Collection '{}' already exists in ChromaDB", collectionName);
                            resultFuture.complete(null);
                        } else {
                            // Create the collection
                            createCollection(collectionName, resultFuture);
                        }
                    } catch (Exception e) {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "Error processing ChromaDB collections response", e));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "Failed to connect to ChromaDB to retrieve collections", ex));
                }

                @Override
                public void cancelled() {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "Request to ChromaDB was cancelled"));
                }
            });
        } catch (Exception e) {
            resultFuture.completeExceptionally(new VectorStoreException(
                    "Failed to build request for ChromaDB collections", e));
        }

        return resultFuture;
    }

    private void createCollection(String collectionName, CompletableFuture<Void> resultFuture) {
        try {
            URI createUri = buildChromaUri(COLLECTIONS_ENDPOINT);
            String requestBody = buildCreateCollectionBody(collectionName);

            SimpleHttpRequest request = SimpleRequestBuilder.post(createUri)
                    .setBody(requestBody, ContentType.APPLICATION_JSON)
                    .build();

            httpClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() == 200) {
                        log.info("Successfully created collection '{}' in ChromaDB", collectionName);
                        resultFuture.complete(null);
                    } else if (response.getCode() == 409) {
                        // Collection already exists (race condition)
                        log.warn("Collection '{}' already exists (409 Conflict)", collectionName);
                        resultFuture.complete(null);
                    } else {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "Failed to create collection in ChromaDB. Status: " + response.getCode() + 
                                ", Response: " + response.getBodyText()));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "Failed to connect to ChromaDB to create collection", ex));
                }

                @Override
                public void cancelled() {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "Request to create ChromaDB collection was cancelled"));
                }
            });
        } catch (Exception e) {
            resultFuture.completeExceptionally(new VectorStoreException(
                    "Failed to build request for creating ChromaDB collection", e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> upsertEmbeddingsAsync(String collectionName, List<VectorEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            log.warn("Attempt to upsert empty or null list of embeddings to collection '{}'", collectionName);
            return CompletableFuture.completedFuture(null);
        }

        try {
            URI upsertUri = buildChromaUri(COLLECTIONS_ENDPOINT + "/" + collectionName + "/upsert");
            String requestBody = buildUpsertBody(entries);

            return executeVoidRequest(upsertUri, "POST", requestBody,
                    "Upserting " + entries.size() + " embeddings to collection '" + collectionName + "'");
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new VectorStoreException(
                    "Failed to build upsert request for ChromaDB", e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<List<VectorEntry>> searchSimilarEmbeddingsAsync(
            String collectionName,
            List<Float> queryEmbedding,
            int k,
            Map<String, Object> metadataFilter) {
        
        if (queryEmbedding == null || queryEmbedding.isEmpty()) {
            log.error("Cannot search with null or empty query embedding");
            return CompletableFuture.failedFuture(new VectorStoreException(
                    "Query embedding cannot be null or empty"));
        }

        CompletableFuture<List<VectorEntry>> resultFuture = new CompletableFuture<>();

        try {
            URI queryUri = buildChromaUri(COLLECTIONS_ENDPOINT + "/" + collectionName + "/query");
            String requestBody = buildQueryBody(queryEmbedding, k, metadataFilter);

            SimpleHttpRequest request = SimpleRequestBuilder.post(queryUri)
                    .setBody(requestBody, ContentType.APPLICATION_JSON)
                    .build();

            httpClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() != 200) {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "ChromaDB query failed with status " + response.getCode() + 
                                ": " + response.getBodyText()));
                        return;
                    }

                    try {
                        List<VectorEntry> results = parseQueryResponse(response.getBodyText());
                        log.debug("Query returned {} results from collection '{}'", 
                                results.size(), collectionName);
                        resultFuture.complete(results);
                    } catch (Exception e) {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "Failed to parse ChromaDB query response", e));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "ChromaDB query request failed", ex));
                }

                @Override
                public void cancelled() {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "ChromaDB query request was cancelled"));
                }
            });
        } catch (Exception e) {
            resultFuture.completeExceptionally(new VectorStoreException(
                    "Failed to build ChromaDB query request", e));
        }

        return resultFuture;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> deleteEmbeddingsByMetadataAsync(
            String collectionName, Map<String, Object> metadataFilter) {
        
        if (metadataFilter == null || metadataFilter.isEmpty()) {
            return CompletableFuture.failedFuture(new VectorStoreException(
                    "Cannot delete with null or empty metadata filter. This is a safeguard against unintended deletion."));
        }

        try {
            URI deleteUri = buildChromaUri(COLLECTIONS_ENDPOINT + "/" + collectionName + "/delete");
            String requestBody = buildDeleteBody(null, metadataFilter);

            log.info("Deleting embeddings from collection '{}' with filter: {}", 
                    collectionName, metadataFilter);
            
            return executeVoidRequest(deleteUri, "POST", requestBody,
                    "Deleting embeddings from collection '" + collectionName + "' with metadata filter");
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new VectorStoreException(
                    "Failed to build ChromaDB delete request", e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Void> deleteEmbeddingsByIdsAsync(String collectionName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("Attempt to delete with empty or null list of IDs from collection '{}'", collectionName);
            return CompletableFuture.completedFuture(null);
        }

        try {
            URI deleteUri = buildChromaUri(COLLECTIONS_ENDPOINT + "/" + collectionName + "/delete");
            String requestBody = buildDeleteBody(ids, null);

            log.info("Deleting {} embeddings by IDs from collection '{}'", ids.size(), collectionName);
            
            return executeVoidRequest(deleteUri, "POST", requestBody,
                    "Deleting " + ids.size() + " embeddings by IDs from collection '" + collectionName + "'");
        } catch (Exception e) {
            return CompletableFuture.failedFuture(new VectorStoreException(
                    "Failed to build ChromaDB delete by IDs request", e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<Long> countEmbeddingsAsync(String collectionName) {
        CompletableFuture<Long> resultFuture = new CompletableFuture<>();

        try {
            URI countUri = buildChromaUri(COLLECTIONS_ENDPOINT + "/" + collectionName + "/count");
            SimpleHttpRequest request = SimpleRequestBuilder.get(countUri).build();

            httpClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() != 200) {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "ChromaDB count failed with status " + response.getCode() + 
                                ": " + response.getBodyText()));
                        return;
                    }

                    try {
                        JsonNode jsonResponse = objectMapper.readTree(response.getBodyText());
                        long count = 0;
                        if (jsonResponse.has("count") && jsonResponse.get("count").isNumber()) {
                            count = jsonResponse.get("count").asLong();
                        }
                        resultFuture.complete(count);
                    } catch (Exception e) {
                        resultFuture.completeExceptionally(new VectorStoreException(
                                "Failed to parse ChromaDB count response", e));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "ChromaDB count request failed", ex));
                }

                @Override
                public void cancelled() {
                    resultFuture.completeExceptionally(new VectorStoreException(
                            "ChromaDB count request was cancelled"));
                }
            });
        } catch (Exception e) {
            resultFuture.completeExceptionally(new VectorStoreException(
                    "Failed to build ChromaDB count request", e));
        }

        return resultFuture;
    }

    // Helper methods

    private URI buildChromaUri(String path) throws URISyntaxException {
        return new URIBuilder(chromaDBConfig.getUrl())
                .setPath(path)
                .build();
    }

    private String buildCreateCollectionBody(String collectionName) throws Exception {
        ObjectNode root = objectMapper.createObjectNode();
        root.put("name", collectionName);
        // Set the distance function based on config
        ObjectNode metadata = objectMapper.createObjectNode();
        metadata.put("hnsw:space", chromaDBConfig.getDistanceFunction());
        root.set("metadata", metadata);
        return objectMapper.writeValueAsString(root);
    }

    private String buildUpsertBody(List<VectorEntry> entries) throws Exception {
        // Prepare arrays for ChromaDB upsert endpoint
        List<String> ids = new ArrayList<>(entries.size());
        List<List<Float>> embeddings = new ArrayList<>(entries.size());
        List<Map<String, Object>> metadatas = new ArrayList<>(entries.size());
        List<String> documents = new ArrayList<>(entries.size());

        for (VectorEntry entry : entries) {
            ids.add(entry.getId());
            embeddings.add(entry.getEmbedding());
            metadatas.add(entry.getMetadata() != null 
                ? entry.getMetadata() 
                : new HashMap<>());
            // If document is null, add empty string to keep arrays aligned
            documents.add(entry.getDocument() != null ? entry.getDocument() : "");
        }

        // Build the JSON structure ChromaDB expects
        Map<String, Object> requestMap = new HashMap<>();
        requestMap.put("ids", ids);
        requestMap.put("embeddings", embeddings);
        requestMap.put("metadatas", metadatas);
        requestMap.put("documents", documents);

        return objectMapper.writeValueAsString(requestMap);
    }

    private String buildQueryBody(List<Float> queryEmbedding, int k, Map<String, Object> metadataFilter) throws Exception {
        Map<String, Object> requestMap = new HashMap<>();
        
        // Single query embedding wrapped in a list (ChromaDB expects an array of query embeddings)
        requestMap.put("query_embeddings", Collections.singletonList(queryEmbedding));
        requestMap.put("n_results", k);
        
        // Include distance, documents, and metadatas in results
        requestMap.put("include", List.of("distances", "documents", "metadatas"));
        
        // Add metadata filter if provided
        if (metadataFilter != null && !metadataFilter.isEmpty()) {
            requestMap.put("where", metadataFilter); // ChromaDB handles basic equality filters directly
        }

        return objectMapper.writeValueAsString(requestMap);
    }

    private String buildDeleteBody(List<String> ids, Map<String, Object> metadataFilter) throws Exception {
        Map<String, Object> requestMap = new HashMap<>();
        
        if (ids != null && !ids.isEmpty()) {
            requestMap.put("ids", ids);
        } else if (metadataFilter != null && !metadataFilter.isEmpty()) {
            requestMap.put("where", metadataFilter);
        }

        return objectMapper.writeValueAsString(requestMap);
    }

    private List<VectorEntry> parseQueryResponse(String responseBody) throws Exception {
        JsonNode response = objectMapper.readTree(responseBody);
        
        // ChromaDB query response structure has parallels arrays: ids, distances, documents, metadatas
        if (!response.has("ids") || !response.get("ids").isArray()) {
            return Collections.emptyList();
        }
        
        // Extract arrays from response
        JsonNode ids = response.get("ids");
        JsonNode distances = response.has("distances") ? response.get("distances") : null;
        JsonNode documents = response.has("documents") ? response.get("documents") : null;
        JsonNode metadatas = response.has("metadatas") ? response.get("metadatas") : null;
        
        // Should be an array of arrays since ChromaDB supports multiple query embeddings
        if (ids.size() == 0 || !ids.get(0).isArray()) {
            return Collections.emptyList();
        }
        
        // We only sent one query embedding, so we only care about the first set of results
        JsonNode firstIds = ids.get(0);
        JsonNode firstDistances = distances != null && distances.size() > 0 ? distances.get(0) : null;
        JsonNode firstDocuments = documents != null && documents.size() > 0 ? documents.get(0) : null;
        JsonNode firstMetadatas = metadatas != null && metadatas.size() > 0 ? metadatas.get(0) : null;
        
        int resultCount = firstIds.size();
        List<VectorEntry> results = new ArrayList<>(resultCount);
        
        for (int i = 0; i < resultCount; i++) {
            String id = firstIds.get(i).asText();
            
            // Create metadata map from JSON
            Map<String, Object> metadata = new HashMap<>();
            if (firstMetadatas != null && firstMetadatas.size() > i) {
                JsonNode meta = firstMetadatas.get(i);
                if (meta.isObject()) {
                    meta.fields().forEachRemaining(entry -> 
                        metadata.put(entry.getKey(), deserializeJsonValue(entry.getValue())));
                }
            }
            
            // Add distance to metadata if available
            if (firstDistances != null && firstDistances.size() > i) {
                metadata.put("_distance", firstDistances.get(i).asDouble());
            }
            
            // Get document if available
            String document = null;
            if (firstDocuments != null && firstDocuments.size() > i) {
                document = firstDocuments.get(i).asText();
            }
            
            // Build the VectorEntry (note: embedding is null as ChromaDB doesn't return the embeddings by default)
            VectorEntry entry = VectorEntry.builder()
                .id(id)
                .metadata(metadata)
                .document(document)
                .build();
                
            results.add(entry);
        }
        
        return results;
    }

    private Object deserializeJsonValue(JsonNode value) {
        if (value.isTextual()) {
            return value.asText();
        } else if (value.isInt()) {
            return value.asInt();
        } else if (value.isLong()) {
            return value.asLong();
        } else if (value.isDouble() || value.isFloat()) {
            return value.asDouble();
        } else if (value.isBoolean()) {
            return value.asBoolean();
        } else if (value.isNull()) {
            return null;
        } else if (value.isArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonNode item : value) {
                list.add(deserializeJsonValue(item));
            }
            return list;
        } else if (value.isObject()) {
            Map<String, Object> map = new HashMap<>();
            value.fields().forEachRemaining(entry -> 
                map.put(entry.getKey(), deserializeJsonValue(entry.getValue())));
            return map;
        }
        // Default fallback
        return value.toString();
    }

    private CompletableFuture<Void> executeVoidRequest(URI uri, String method, String body, String operationDescription) {
        CompletableFuture<Void> resultFuture = new CompletableFuture<>();
        
        SimpleHttpRequest request;
        if ("POST".equals(method)) {
            request = SimpleRequestBuilder.post(uri)
                .setBody(body, ContentType.APPLICATION_JSON)
                .build();
        } else if ("PUT".equals(method)) {
            request = SimpleRequestBuilder.put(uri)
                .setBody(body, ContentType.APPLICATION_JSON)
                .build();
        } else if ("DELETE".equals(method)) {
            request = SimpleRequestBuilder.delete(uri)
                .setBody(body, ContentType.APPLICATION_JSON)
                .build();
        } else {
            request = SimpleRequestBuilder.get(uri).build();
        }
        
        httpClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
            @Override
            public void completed(SimpleHttpResponse response) {
                if (response.getCode() >= 200 && response.getCode() < 300) {
                    log.debug("Successfully completed ChromaDB operation: {}", operationDescription);
                    resultFuture.complete(null);
                } else {
                    resultFuture.completeExceptionally(new VectorStoreException(
                        "ChromaDB operation failed with status " + response.getCode() + 
                        ": " + response.getBodyText()));
                }
            }

            @Override
            public void failed(Exception ex) {
                resultFuture.completeExceptionally(new VectorStoreException(
                    "ChromaDB request failed: " + operationDescription, ex));
            }

            @Override
            public void cancelled() {
                resultFuture.completeExceptionally(new VectorStoreException(
                    "ChromaDB request was cancelled: " + operationDescription));
            }
        });
        
        return resultFuture;
    }
} 
