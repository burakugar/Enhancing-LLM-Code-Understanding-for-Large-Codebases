package com.localllm.assistant.vectorstore.impl;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
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
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;

/**
 * Implementation of VectorStoreClient that interacts with a ChromaDB instance via its HTTP API.
 * Handles operations like creating collections, upserting, querying, and deleting embeddings.
 */
@Service
public class ChromaDBClientImpl implements VectorStoreClient {

    private static final Logger log = LoggerFactory.getLogger(ChromaDBClientImpl.class);
    private static final String API_BASE_PATH = "/api/v1";

    private final ChromaDBConfig chromaDBConfig;
    private final ObjectMapper objectMapper;
    private final CloseableHttpAsyncClient httpAsyncClient;

    /**
     * Constructor with dependency injection for configuration and Jackson ObjectMapper.
     * Initialize the HTTP client.
     *
     * @param chromaDBConfig ChromaDB configuration parameters
     * @param objectMapper Jackson ObjectMapper for JSON processing
     */
    public ChromaDBClientImpl(ChromaDBConfig chromaDBConfig, ObjectMapper objectMapper) {
        this.chromaDBConfig = chromaDBConfig;
        this.objectMapper = objectMapper;
        this.httpAsyncClient = HttpAsyncClients.createDefault();
        log.info("ChromaDBClientImpl initialized with config: {}", chromaDBConfig);
    }

    @PostConstruct
    public void startClient() {
        if (!httpAsyncClient.isRunning()) {
            httpAsyncClient.start();
            log.info("Started Apache HttpAsyncClient for ChromaDB");
        }
    }

    @PreDestroy
    public void stopClient() {
        try {
            if (httpAsyncClient.isRunning()) {
                httpAsyncClient.close();
                log.info("Closed Apache HttpAsyncClient for ChromaDB");
            }
        } catch (IOException e) {
            log.error("Error closing Apache HttpAsyncClient for ChromaDB", e);
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<Void> ensureCollectionExists(String collectionName) {
        log.debug("Ensuring collection exists: {}", collectionName);
        
        // First check if collection exists
        CompletableFuture<Void> result = new CompletableFuture<>();
        
        String listCollectionsUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections";
        SimpleHttpRequest listRequest = SimpleRequestBuilder.get(listCollectionsUrl).build();
        
        httpAsyncClient.execute(listRequest, new FutureCallback<SimpleHttpResponse>() {
            @Override
            public void completed(SimpleHttpResponse response) {
                try {
                    if (response.getCode() != 200) {
                        String error = "Failed to list collections: " + response.getCode() + " - " + response.getBodyText();
                        log.error(error);
                        result.completeExceptionally(new VectorStoreException(error));
                        return;
                    }
                    
                    JsonNode rootNode = objectMapper.readTree(response.getBodyText());
                    boolean collectionExists = false;
                    
                    if (rootNode.has("collections")) {
                        JsonNode collections = rootNode.get("collections");
                        for (JsonNode collection : collections) {
                            if (collection.has("name") && collection.get("name").asText().equals(collectionName)) {
                                collectionExists = true;
                                break;
                            }
                        }
                    }
                    
                    if (collectionExists) {
                        log.info("Collection '{}' already exists in ChromaDB", collectionName);
                        result.complete(null);
                    } else {
                        // Create the collection
                        createCollection(collectionName, result);
                    }
                } catch (Exception e) {
                    log.error("Error checking collection existence: {}", e.getMessage(), e);
                    result.completeExceptionally(new VectorStoreException("Error checking collection existence", e));
                }
            }
            
            @Override
            public void failed(Exception ex) {
                log.error("Failed to list collections: {}", ex.getMessage(), ex);
                result.completeExceptionally(new VectorStoreException("Failed to list collections", ex));
            }
            
            @Override
            public void cancelled() {
                log.warn("List collections request cancelled");
                result.cancel(true);
            }
        });
        
        return result;
    }
    
    private void createCollection(String collectionName, CompletableFuture<Void> result) {
        try {
            String createUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections";
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.put("name", collectionName);
            requestBody.put("metadata", objectMapper.createObjectNode().put("dimension_type", "float"));
            requestBody.put("get_or_create", true); // Create if it doesn't exist
            requestBody.put("embedding_function", objectMapper.createObjectNode());
            
            if (chromaDBConfig.getDistanceFunction() != null) {
                ((ObjectNode)requestBody.get("metadata")).put("hnsw:space", chromaDBConfig.getDistanceFunction());
            }
            
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            SimpleHttpRequest createRequest = SimpleRequestBuilder.post(createUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();
            
            httpAsyncClient.execute(createRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() >= 200 && response.getCode() < 300) {
                        log.info("Successfully created collection '{}'", collectionName);
                        result.complete(null);
                    } else {
                        String error = "Failed to create collection: " + response.getCode() + " - " + response.getBodyText();
                        log.error(error);
                        result.completeExceptionally(new VectorStoreException(error));
                    }
                }
                
                @Override
                public void failed(Exception ex) {
                    log.error("Failed to create collection: {}", ex.getMessage(), ex);
                    result.completeExceptionally(new VectorStoreException("Failed to create collection", ex));
                }
                
                @Override
                public void cancelled() {
                    log.warn("Create collection request cancelled");
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error creating collection: {}", e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error creating collection", e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<Void> upsertEmbeddingsAsync(String collectionName, List<VectorEntry> entries) {
        if (entries == null || entries.isEmpty()) {
            log.warn("No entries to upsert for collection: {}", collectionName);
            return CompletableFuture.completedFuture(null);
        }

        log.info("Upserting {} embeddings to collection '{}'", entries.size(), collectionName);
        CompletableFuture<Void> result = new CompletableFuture<>();
        
        try {
            String upsertUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections/" + collectionName + "/upsert";
            
            // Prepare the request payload according to ChromaDB API structure
            ObjectNode requestBody = objectMapper.createObjectNode();
            
            // Extract IDs
            ArrayNode idsNode = objectMapper.createArrayNode();
            entries.forEach(entry -> idsNode.add(entry.getId()));
            requestBody.set("ids", idsNode);
            
            // Extract embeddings
            ArrayNode embeddingsNode = objectMapper.createArrayNode();
            entries.forEach(entry -> {
                ArrayNode embeddingArray = objectMapper.createArrayNode();
                entry.getEmbedding().forEach(embeddingArray::add);
                embeddingsNode.add(embeddingArray);
            });
            requestBody.set("embeddings", embeddingsNode);
            
            // Extract metadata
            ArrayNode metadataNode = objectMapper.createArrayNode();
            entries.forEach(entry -> {
                try {
                    metadataNode.add(objectMapper.valueToTree(entry.getMetadata()));
                } catch (Exception e) {
                    log.error("Error converting metadata to JSON for entry {}: {}", entry.getId(), e.getMessage());
                    metadataNode.add(objectMapper.createObjectNode()); // Add empty object as fallback
                }
            });
            requestBody.set("metadatas", metadataNode);
            
            // Add documents if available
            ArrayNode documentsNode = objectMapper.createArrayNode();
            boolean hasDocuments = false;
            for (VectorEntry entry : entries) {
                if (entry.getDocument() != null) {
                    documentsNode.add(entry.getDocument());
                    hasDocuments = true;
                } else {
                    documentsNode.add(""); // ChromaDB expects a value even if null
                }
            }
            if (hasDocuments) {
                requestBody.set("documents", documentsNode);
            }
            
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            SimpleHttpRequest upsertRequest = SimpleRequestBuilder.post(upsertUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();
            
            httpAsyncClient.execute(upsertRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() >= 200 && response.getCode() < 300) {
                        log.info("Successfully upserted {} embeddings to collection '{}'", entries.size(), collectionName);
                        result.complete(null);
                    } else {
                        String error = "Failed to upsert embeddings: " + response.getCode() + " - " + response.getBodyText();
                        log.error(error);
                        result.completeExceptionally(new VectorStoreException(error));
                    }
                }
                
                @Override
                public void failed(Exception ex) {
                    log.error("Failed to upsert embeddings: {}", ex.getMessage(), ex);
                    result.completeExceptionally(new VectorStoreException("Failed to upsert embeddings", ex));
                }
                
                @Override
                public void cancelled() {
                    log.warn("Upsert embeddings request cancelled");
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error upserting embeddings: {}", e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error upserting embeddings", e));
        }
        
        return result;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<List<VectorEntry>> searchSimilarEmbeddingsAsync(String collectionName, List<Float> queryEmbedding, int k, Map<String, Object> metadataFilter) {
        log.debug("Searching for similar embeddings in collection '{}' with k={}", collectionName, k);
        
        CompletableFuture<List<VectorEntry>> result = new CompletableFuture<>();
        
        try {
            String queryUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections/" + collectionName + "/query";
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            
            // Add query embedding
            ArrayNode queryEmbeddingNode = objectMapper.createArrayNode();
            queryEmbedding.forEach(queryEmbeddingNode::add);
            
            ArrayNode embeddingsNode = objectMapper.createArrayNode();
            embeddingsNode.add(queryEmbeddingNode);
            requestBody.set("query_embeddings", embeddingsNode);
            
            // Set n_results (k)
            requestBody.put("n_results", k);
            
            // Add metadata filter if provided
            if (metadataFilter != null && !metadataFilter.isEmpty()) {
                requestBody.set("where", objectMapper.valueToTree(metadataFilter));
            }
            
            // Request both embeddings and documents in the response
            requestBody.put("include", objectMapper.createArrayNode()
                    .add("embeddings")
                    .add("metadatas")
                    .add("documents")
                    .add("distances"));
            
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            SimpleHttpRequest queryRequest = SimpleRequestBuilder.post(queryUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();
            
            httpAsyncClient.execute(queryRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        if (response.getCode() >= 200 && response.getCode() < 300) {
                            String responseBody = response.getBodyText();
                            List<VectorEntry> entries = parseQueryResponse(responseBody);
                            log.debug("Found {} similar embeddings in collection '{}'", entries.size(), collectionName);
                            result.complete(entries);
                        } else {
                            String error = "Failed to query embeddings: " + response.getCode() + " - " + response.getBodyText();
                            log.error(error);
                            result.completeExceptionally(new VectorStoreException(error));
                        }
                    } catch (Exception e) {
                        log.error("Error processing query response: {}", e.getMessage(), e);
                        result.completeExceptionally(new VectorStoreException("Error processing query response", e));
                    }
                }
                
                @Override
                public void failed(Exception ex) {
                    log.error("Failed to query embeddings: {}", ex.getMessage(), ex);
                    result.completeExceptionally(new VectorStoreException("Failed to query embeddings", ex));
                }
                
                @Override
                public void cancelled() {
                    log.warn("Query embeddings request cancelled");
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error preparing query request: {}", e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error preparing query request", e));
        }
        
        return result;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<Void> deleteEmbeddingsByMetadataAsync(String collectionName, Map<String, Object> metadataFilter) {
        if (metadataFilter == null || metadataFilter.isEmpty()) {
            return CompletableFuture.failedFuture(new VectorStoreException("Metadata filter cannot be null or empty for safety"));
        }
        
        log.info("Deleting embeddings by metadata filter from collection '{}'", collectionName);
        CompletableFuture<Void> result = new CompletableFuture<>();
        
        try {
            String deleteUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections/" + collectionName + "/delete";
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            requestBody.set("where", objectMapper.valueToTree(metadataFilter));
            
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            SimpleHttpRequest deleteRequest = SimpleRequestBuilder.post(deleteUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();
            
            httpAsyncClient.execute(deleteRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() >= 200 && response.getCode() < 300) {
                        log.info("Successfully deleted embeddings from collection '{}' using metadata filter", collectionName);
                        result.complete(null);
                    } else {
                        String error = "Failed to delete embeddings: " + response.getCode() + " - " + response.getBodyText();
                        log.error(error);
                        result.completeExceptionally(new VectorStoreException(error));
                    }
                }
                
                @Override
                public void failed(Exception ex) {
                    log.error("Failed to delete embeddings: {}", ex.getMessage(), ex);
                    result.completeExceptionally(new VectorStoreException("Failed to delete embeddings", ex));
                }
                
                @Override
                public void cancelled() {
                    log.warn("Delete embeddings request cancelled");
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error deleting embeddings: {}", e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error deleting embeddings", e));
        }
        
        return result;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<Void> deleteEmbeddingsByIdsAsync(String collectionName, List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            log.warn("No IDs provided for deletion from collection '{}'", collectionName);
            return CompletableFuture.completedFuture(null);
        }
        
        log.info("Deleting {} embeddings by IDs from collection '{}'", ids.size(), collectionName);
        CompletableFuture<Void> result = new CompletableFuture<>();
        
        try {
            String deleteUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections/" + collectionName + "/delete";
            
            ObjectNode requestBody = objectMapper.createObjectNode();
            ArrayNode idsNode = objectMapper.createArrayNode();
            ids.forEach(idsNode::add);
            requestBody.set("ids", idsNode);
            
            String requestBodyJson = objectMapper.writeValueAsString(requestBody);
            SimpleHttpRequest deleteRequest = SimpleRequestBuilder.post(deleteUrl)
                    .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                    .build();
            
            httpAsyncClient.execute(deleteRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    if (response.getCode() >= 200 && response.getCode() < 300) {
                        log.info("Successfully deleted {} embeddings by ID from collection '{}'", ids.size(), collectionName);
                        result.complete(null);
                    } else {
                        String error = "Failed to delete embeddings by ID: " + response.getCode() + " - " + response.getBodyText();
                        log.error(error);
                        result.completeExceptionally(new VectorStoreException(error));
                    }
                }
                
                @Override
                public void failed(Exception ex) {
                    log.error("Failed to delete embeddings by ID: {}", ex.getMessage(), ex);
                    result.completeExceptionally(new VectorStoreException("Failed to delete embeddings by ID", ex));
                }
                
                @Override
                public void cancelled() {
                    log.warn("Delete embeddings by ID request cancelled");
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error deleting embeddings by ID: {}", e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error deleting embeddings by ID", e));
        }
        
        return result;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_INDEXING)
    public CompletableFuture<Long> countEmbeddingsAsync(String collectionName) {
        log.debug("Counting embeddings in collection '{}'", collectionName);
        CompletableFuture<Long> result = new CompletableFuture<>();
        
        try {
            String countUrl = chromaDBConfig.getUrl() + API_BASE_PATH + "/collections/" + collectionName + "/count";
            SimpleHttpRequest countRequest = SimpleRequestBuilder.get(countUrl).build();
            
            httpAsyncClient.execute(countRequest, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        if (response.getCode() >= 200 && response.getCode() < 300) {
                            JsonNode rootNode = objectMapper.readTree(response.getBodyText());
                            if (rootNode.has("count")) {
                                long count = rootNode.get("count").asLong();
                                log.debug("Collection '{}' contains {} embeddings", collectionName, count);
                                result.complete(count);
                            } else {
                                result.completeExceptionally(new VectorStoreException("Response missing 'count' field"));
                            }
                        } else {
                            String error = "Failed to count embeddings: " + response.getCode() + " - " + response.getBodyText();
                            log.error(error);
                            result.completeExceptionally(new VectorStoreException(error));
                        }
                    } catch (Exception e) {
                        log.error("Error processing count response: {}", e.getMessage(), e);
                        result.completeExceptionally(new VectorStoreException("Error processing count response", e));
                    }
                }
                
                @Override
                public void failed(Exception ex) {
                    log.error("Failed to count embeddings: {}", ex.getMessage(), ex);
                    result.completeExceptionally(new VectorStoreException("Failed to count embeddings", ex));
                }
                
                @Override
                public void cancelled() {
                    log.warn("Count embeddings request cancelled");
                    result.cancel(true);
                }
            });
        } catch (Exception e) {
            log.error("Error counting embeddings: {}", e.getMessage(), e);
            result.completeExceptionally(new VectorStoreException("Error counting embeddings", e));
        }
        
        return result;
    }
    
    /**
     * Parses ChromaDB query response and converts it to a list of VectorEntry objects.
     *
     * @param responseJson The JSON response string from ChromaDB
     * @return A list of VectorEntry objects
     * @throws IOException If JSON parsing fails
     */
    private List<VectorEntry> parseQueryResponse(String responseJson) throws IOException {
        JsonNode rootNode = objectMapper.readTree(responseJson);
        
        // ChromaDB returns arrays of ids, embeddings, metadatas, distances and documents
        JsonNode idsNode = rootNode.path("ids").get(0); // First query's results
        JsonNode embeddingsNode = rootNode.path("embeddings").get(0);
        JsonNode metadatasNode = rootNode.path("metadatas").get(0);
        JsonNode distancesNode = rootNode.path("distances").get(0);
        JsonNode documentsNode = rootNode.has("documents") ? rootNode.path("documents").get(0) : null;
        
        if (idsNode == null || embeddingsNode == null || metadatasNode == null) {
            throw new VectorStoreException("Incomplete response from ChromaDB");
        }
        
        List<VectorEntry> results = new ArrayList<>();
        
        for (int i = 0; i < idsNode.size(); i++) {
            String id = idsNode.get(i).asText();
            
            // Parse embedding
            List<Float> embedding = new ArrayList<>();
            JsonNode embeddingNode = embeddingsNode.get(i);
            for (int j = 0; j < embeddingNode.size(); j++) {
                embedding.add((float) embeddingNode.get(j).asDouble());
            }
            
            // Parse metadata
            Map<String, Object> metadata = new HashMap<>();
            JsonNode currentMetadata = metadatasNode.get(i);
            if (currentMetadata != null && !currentMetadata.isNull()) {
                metadata = objectMapper.convertValue(currentMetadata, Map.class);
            }
            
            // Add distance score to metadata if available
            if (distancesNode != null && i < distancesNode.size()) {
                metadata.put("distance", distancesNode.get(i).asDouble());
            }
            
            // Get document if available
            String document = null;
            if (documentsNode != null && i < documentsNode.size() && !documentsNode.get(i).isNull() && !documentsNode.get(i).asText().isEmpty()) {
                document = documentsNode.get(i).asText();
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
} 
