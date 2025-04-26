package com.localllm.assistant.embedding.impl;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.config.AsyncConfig;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localllm.assistant.config.OllamaConfig;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.exception.EmbeddingException;
import com.localllm.assistant.parser.model.CodeSegment;

/**
 * Implementation of EmbeddingService that uses Ollama API to generate embeddings.
 */
@Service
public class OllamaEmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingServiceImpl.class);
    private final CloseableHttpClient httpClient;
    private final OllamaConfig ollamaConfig;
    private final ObjectMapper objectMapper;

    /**
     * URL path for Ollama embeddings API.
     */
    private static final String EMBEDDINGS_PATH = "/api/embeddings";

    /**
     * Constructor with dependency injection.
     *
     * @param httpClient   HttpClient configured for Ollama.
     * @param ollamaConfig Configuration for Ollama connections.
     * @param objectMapper JSON mapper for request/response serialization.
     */
    public OllamaEmbeddingServiceImpl(CloseableHttpClient httpClient, OllamaConfig ollamaConfig, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.ollamaConfig = ollamaConfig;
        this.objectMapper = objectMapper;
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<List<Float>> generateEmbeddingAsync(String text) {
        log.debug("Generating embedding for text of length {}", text.length());

        try {
            List<Float> embedding = callOllamaEmbeddingApi(text);
            return CompletableFuture.completedFuture(embedding);
        } catch (Exception e) {
            log.error("Failed to generate embedding: {}", e.getMessage(), e);
            return CompletableFuture.failedFuture(
                    new EmbeddingException("Failed to generate embedding from Ollama: " + e.getMessage(), e));
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<List<List<Float>>> generateEmbeddingsAsync(List<CodeSegment> segments) {
        log.info("Generating embeddings for {} code segments", segments.size());
        List<List<Float>> embeddings = new ArrayList<>(segments.size());

        // Process each segment individually - could be optimized with batching if Ollama API supports it
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        
        for (int i = 0; i < segments.size(); i++) {
            final int index = i;
            // Initialize with nulls to maintain order
            embeddings.add(null);
            
            CodeSegment segment = segments.get(i);
            if (segment.getContent() == null || segment.getContent().isBlank()) {
                log.warn("Skipping empty segment at index {}", i);
                continue;
            }
            
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    List<Float> embedding = callOllamaEmbeddingApi(segment.getContent());
                    log.debug("Generated embedding for segment {}: {} values", segment.getId(), embedding.size());
                    synchronized (embeddings) {
                        embeddings.set(index, embedding);
                    }
                } catch (Exception e) {
                    log.error("Error generating embedding for segment {}: {}", segment.getId(), e.getMessage(), e);
                    // Keep null in the list for this segment
                }
            });
            
            futures.add(future);
        }

        return CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenApply(v -> {
                    log.info("Completed embedding generation for {} segments", segments.size());
                    return embeddings;
                })
                .exceptionally(ex -> {
                    log.error("Failed to generate batch embeddings", ex);
                    throw new EmbeddingException("Batch embedding generation failed", ex);
                });
    }

    /**
     * Makes the HTTP request to Ollama API to generate the embedding.
     *
     * @param text The text content to embed.
     * @return A list of floating point values representing the embedding vector.
     * @throws IOException If the HTTP request fails.
     * @throws EmbeddingException If Ollama returns an error or response parsing fails.
     */
    @SuppressWarnings("unchecked")
    private List<Float> callOllamaEmbeddingApi(String text) throws IOException, EmbeddingException {
        String url = ollamaConfig.getBaseUrl() + EMBEDDINGS_PATH;
        HttpPost httpPost = new HttpPost(url);
        
        // Build the request payload
        Map<String, Object> requestBody = new HashMap<>();
        requestBody.put("model", ollamaConfig.getEmbeddingModel());
        requestBody.put("prompt", text);
        
        // Set the request content
        StringEntity requestEntity = new StringEntity(
                objectMapper.writeValueAsString(requestBody),
                ContentType.APPLICATION_JSON);
        httpPost.setEntity(requestEntity);
        
        // Execute the request
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            int statusCode = response.getCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            
            if (statusCode != 200) {
                throw new EmbeddingException("Ollama API returned non-200 status code: " + statusCode + " - " + responseBody);
            }
            
            try {
                Map<String, Object> responseJson = objectMapper.readValue(responseBody, Map.class);
                
                if (!responseJson.containsKey("embedding")) {
                    throw new EmbeddingException("Ollama response missing 'embedding' field: " + responseBody);
                }
                
                List<Number> rawEmbeddings = (List<Number>) responseJson.get("embedding");
                return convertToFloatList(rawEmbeddings);
                
            } catch (Exception e) {
                throw new EmbeddingException("Failed to parse Ollama embedding response: " + e.getMessage(), e);
            }
        } catch (ParseException e) {
            throw new RuntimeException(e);
        }
    }

    /**
     * Converts a list of Number objects to a list of Float.
     * This is necessary because Jackson deserializes numeric arrays as List<Number>.
     *
     * @param numbers The list of Numbers to convert.
     * @return A list of Float values.
     */
    private List<Float> convertToFloatList(List<Number> numbers) {
        List<Float> floats = new ArrayList<>(numbers.size());
        for (Number n : numbers) {
            floats.add(n.floatValue());
        }
        return floats;
    }
} 
