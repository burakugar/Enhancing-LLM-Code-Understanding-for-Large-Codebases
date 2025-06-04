package com.localllm.assistant.embedding.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.OllamaConfig;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.exception.EmbeddingException;
import com.localllm.assistant.parser.model.CodeSegment;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

@Service
public class OllamaEmbeddingServiceImpl implements EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(OllamaEmbeddingServiceImpl.class);
    private final CloseableHttpAsyncClient httpAsyncClient;
    private final OllamaConfig ollamaConfig;
    private final ObjectMapper objectMapper;
    private final URI embeddingsUri;
    private final Executor embeddingExecutor;

    @Value("${ollama.embedding.batchSize:1}")
    private int ollamaClientBatchSize;

    public OllamaEmbeddingServiceImpl(
        @Qualifier("sharedHttpAsyncClient") CloseableHttpAsyncClient httpAsyncClient,
        OllamaConfig ollamaConfig,
        ObjectMapper objectMapper,
        @Qualifier(AsyncConfig.TASK_EXECUTOR_EMBEDDING) Executor embeddingExecutor) {
        this.httpAsyncClient = httpAsyncClient;
        this.ollamaConfig = ollamaConfig;
        this.objectMapper = objectMapper;
        this.embeddingExecutor = embeddingExecutor;
        try {
            this.embeddingsUri = new URI(ollamaConfig.getBaseUrl() + "/api/embeddings");
        } catch (URISyntaxException e) {
            log.error("Invalid Ollama base URL: {}", ollamaConfig.getBaseUrl(), e);
            throw new EmbeddingException("Invalid Ollama base URL configuration", e);
        }
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<List<Float>> generateEmbeddingAsync(String text) {
        log.debug("Generating single embedding for text length {}", text.length());
        if (text == null || text.isBlank()) {
            log.warn("Attempted to generate embedding for null or blank text.");
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        return callOllamaEmbeddingApi(List.of(text))
            .thenApplyAsync(embeddingsList -> {
                if (embeddingsList == null || embeddingsList.isEmpty() || embeddingsList.get(0) == null || embeddingsList.get(0).isEmpty()) {
                    log.warn("Ollama returned null or empty embedding for single text request starting with: '{}...'",
                        text.substring(0, Math.min(50, text.length())));
                    return Collections.<Float>emptyList();
                }
                return embeddingsList.get(0);
            }, embeddingExecutor)
            .exceptionally(ex -> {
                log.error("Failed to generate single embedding for text starting with: '{}...': {}", text.substring(0, Math.min(50, text.length())),
                    ex.getMessage(), ex);
                return Collections.emptyList();
            });
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<List<List<Float>>> generateEmbeddingsAsync(List<CodeSegment> segments) {
        log.info("Generating embeddings for {} code segments, client-side batch size: {}", segments.size(), ollamaClientBatchSize);

        List<List<Float>> finalEmbeddingsInOrder = new ArrayList<>(Collections.nCopies(segments.size(), Collections.emptyList()));
        List<EmbeddingTask> tasksToProcess = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            CodeSegment segment = segments.get(i);
            if (segment != null && segment.getContent() != null && !segment.getContent().isBlank()) {
                tasksToProcess.add(new EmbeddingTask(i, segment.getContent()));
            }
        }

        if (tasksToProcess.isEmpty()) {
            log.warn("No non-empty segments found to generate embeddings for.");
            return CompletableFuture.completedFuture(finalEmbeddingsInOrder);
        }

        List<CompletableFuture<Void>> allBatchProcessingFutures = new ArrayList<>();
        int numActualBatches = (tasksToProcess.size() + ollamaClientBatchSize - 1) / ollamaClientBatchSize;
        log.info("Splitting {} non-blank contents into {} batches for Ollama.", tasksToProcess.size(), numActualBatches);

        for (int i = 0; i < tasksToProcess.size(); i += ollamaClientBatchSize) {
            List<EmbeddingTask> currentBatchTasks = tasksToProcess.subList(i, Math.min(i + ollamaClientBatchSize, tasksToProcess.size()));
            List<String> currentBatchContents = currentBatchTasks.stream().map(EmbeddingTask::getContent).collect(Collectors.toList());

            if (!currentBatchContents.isEmpty()) {
                int batchNumForLogging = (i / ollamaClientBatchSize) + 1;
                log.debug("Submitting Ollama embedding batch {}/{} of size {}", batchNumForLogging, numActualBatches, currentBatchContents.size());

                CompletableFuture<Void> batchCompletionFuture = callOllamaEmbeddingApi(currentBatchContents)
                    .thenAcceptAsync(batchEmbeddings -> {
                        if (batchEmbeddings != null && batchEmbeddings.size() == currentBatchTasks.size()) {
                            for (int j = 0; j < currentBatchTasks.size(); j++) {
                                EmbeddingTask task = currentBatchTasks.get(j);
                                List<Float> embedding = batchEmbeddings.get(j);
                                if (embedding != null && !embedding.isEmpty()) {
                                    finalEmbeddingsInOrder.set(task.getOriginalIndex(), embedding);
                                } else {
                                    log.warn("Received null/empty embedding for content (original index {}) in batch {}/{}.", task.getOriginalIndex(),
                                        batchNumForLogging, numActualBatches);
                                }
                            }
                            log.debug("Successfully processed results for Ollama batch {}/{}", batchNumForLogging, numActualBatches);
                        } else {
                            log.error(
                                "Mismatch or null result in returned embeddings for batch {}/{}. Expected {}, got {}. All prompts in this batch will have empty embeddings.",
                                batchNumForLogging, numActualBatches, batchEmbeddings != null ? batchEmbeddings.size() : "null");
                        }
                    }, embeddingExecutor)
                    .exceptionally(ex -> {
                        log.error("Processing Ollama batch {}/{} failed exceptionally: {}", batchNumForLogging, numActualBatches, ex.getMessage(),
                            ex);
                        return null;
                    });
                allBatchProcessingFutures.add(batchCompletionFuture);
            }
        }

        return CompletableFuture.allOf(allBatchProcessingFutures.toArray(new CompletableFuture[0]))
            .thenApplyAsync(v -> {
                log.info("All Ollama embedding batches processed. Final list size: {}", finalEmbeddingsInOrder.size());
                return finalEmbeddingsInOrder;
            }, embeddingExecutor)
            .exceptionally(ex -> {
                log.error("Catastrophic failure during embedding batch processing orchestration: {}", ex.getMessage(), ex);
                return Collections.nCopies(segments.size(), Collections.emptyList());
            });
    }

    private static class EmbeddingTask {
        private final int originalIndex;
        private final String content;

        public EmbeddingTask(int originalIndex, String content) {
            this.originalIndex = originalIndex;
            this.content = content;
        }

        public int getOriginalIndex() {
            return originalIndex;
        }

        public String getContent() {
            return content;
        }
    }

    @SuppressWarnings("unchecked")
    private CompletableFuture<List<List<Float>>> callOllamaEmbeddingApi(List<String> promptsInBatch) {
        CompletableFuture<List<List<Float>>> future = new CompletableFuture<>();
        if (promptsInBatch == null || promptsInBatch.isEmpty()) {
            return CompletableFuture.completedFuture(Collections.emptyList());
        }
        List<List<Float>> resultsForThisBatch = new ArrayList<>(Collections.nCopies(promptsInBatch.size(), Collections.emptyList()));

        try {
            Map<String, Object> requestBodyMap = new HashMap<>();
            requestBodyMap.put("model", ollamaConfig.getEmbeddingModel());
            if (promptsInBatch.size() == 1) {
                requestBodyMap.put("prompt", promptsInBatch.get(0));
            } else {
                requestBodyMap.put("prompts", promptsInBatch);
            }

            String requestBodyJson = objectMapper.writeValueAsString(requestBodyMap);
            SimpleHttpRequest request = SimpleRequestBuilder.post(embeddingsUri)
                .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                .build();

            log.debug("Sending Ollama embedding request for a batch of {} prompts using model '{}'. First prompt starts with: '{}...'",
                promptsInBatch.size(), ollamaConfig.getEmbeddingModel(),
                promptsInBatch.get(0).substring(0, Math.min(50, promptsInBatch.get(0).length())));

            httpAsyncClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        String responseBody = response.getBodyText();
                        int statusCode = response.getCode();

                        if (statusCode != 200) {
                            log.error("Ollama API Error for batch: Status Code {}, Body: {}. Prompts count: {}", statusCode, responseBody,
                                promptsInBatch.size());
                            future.complete(resultsForThisBatch);
                            return;
                        }

                        JsonNode responseJsonNode = objectMapper.readTree(responseBody);

                        if (promptsInBatch.size() == 1 && responseJsonNode.has("embedding")) {
                            JsonNode embeddingNode = responseJsonNode.get("embedding");
                            if (embeddingNode.isArray() && !embeddingNode.isEmpty()) {
                                List<Number> rawEmbedding = new ArrayList<>();
                                for (JsonNode numNode : embeddingNode) {
                                    rawEmbedding.add(numNode.numberValue());
                                }
                                resultsForThisBatch.set(0, convertToFloatList(rawEmbedding));
                            } else {
                                log.warn("Ollama returned single 'embedding' but it was not a valid array or was empty. Response: {}", responseBody);
                            }
                        } else if (promptsInBatch.size() > 1 && responseJsonNode.has("embeddings")) {
                            JsonNode embeddingsArrayNode = responseJsonNode.get("embeddings");
                            if (embeddingsArrayNode.isArray()) {
                                if (embeddingsArrayNode.size() != promptsInBatch.size()) {
                                    log.error(
                                        "Ollama returned {} embedding lists in 'embeddings' field for a batch of {} prompts! Mismatch. Response: {}",
                                        embeddingsArrayNode.size(), promptsInBatch.size(),
                                        responseBody.substring(0, Math.min(500, responseBody.length())));

                                    for (int i = 0; i < embeddingsArrayNode.size() && i < promptsInBatch.size(); i++) {
                                        JsonNode singleEmbeddingArrayNode = embeddingsArrayNode.get(i);
                                        if (singleEmbeddingArrayNode.isArray() && !singleEmbeddingArrayNode.isEmpty()) {
                                            List<Number> rawEmbedding = new ArrayList<>();
                                            for (JsonNode numNode : singleEmbeddingArrayNode) {
                                                rawEmbedding.add(numNode.numberValue());
                                            }
                                            resultsForThisBatch.set(i, convertToFloatList(rawEmbedding));
                                        }
                                    }
                                } else {
                                    for (int i = 0; i < embeddingsArrayNode.size(); i++) {
                                        JsonNode singleEmbeddingArrayNode = embeddingsArrayNode.get(i);
                                        if (singleEmbeddingArrayNode.isArray() && !singleEmbeddingArrayNode.isEmpty()) {
                                            List<Number> rawEmbedding = new ArrayList<>();
                                            for (JsonNode numNode : singleEmbeddingArrayNode) {
                                                rawEmbedding.add(numNode.numberValue());
                                            }
                                            resultsForThisBatch.set(i, convertToFloatList(rawEmbedding));
                                        } else {
                                            log.warn("Item at index {} in 'embeddings' array was not a valid array or was empty.", i);
                                        }
                                    }
                                }
                            } else {
                                log.error("Ollama response 'embeddings' field was not an array. Response: {}", responseBody);
                            }
                        } else {
                            log.error(
                                "Ollama response missing 'embedding' (for single, size 1 batch) or 'embeddings' (for batch size > 1) field, or structure was unexpected. Response: {}. Prompts count: {}",
                                responseBody, promptsInBatch.size());
                        }
                        log.debug("Successfully received and parsed response for a batch of {} prompts. Resulting valid embeddings in this batch: {}",
                            promptsInBatch.size(), resultsForThisBatch.stream().filter(e -> !e.isEmpty()).count());
                        future.complete(resultsForThisBatch);
                    } catch (Exception e) {
                        log.error("Failed to parse Ollama embedding response JSON for batch (prompts: {}): {}", promptsInBatch.size(), e.getMessage(),
                            e);
                        future.complete(resultsForThisBatch);
                    }
                }

                @Override
                public void failed(Exception ex) {
                    log.error("Ollama embedding request failed for batch (prompts: {}): {}", promptsInBatch.size(), ex.getMessage(), ex);
                    future.complete(resultsForThisBatch);
                }

                @Override
                public void cancelled() {
                    log.warn("Ollama embedding request cancelled for batch (prompts: {})", promptsInBatch.size());
                    future.complete(resultsForThisBatch);
                }
            });

        } catch (JsonProcessingException e) {
            log.error("Failed to serialize Ollama request body for batch (prompts: {})", promptsInBatch.size(), e);
            future.complete(resultsForThisBatch);
        } catch (Exception e) {
            log.error("Unexpected error preparing Ollama embedding request for batch (prompts: {})", promptsInBatch.size(), e);
            future.complete(resultsForThisBatch);
        }
        return future;
    }

    private List<Float> convertToFloatList(List<Number> numbers) {
        if (numbers == null || numbers.isEmpty()) {
            return Collections.emptyList();
        }
        return numbers.stream()
            .map(n -> n != null ? n.floatValue() : 0.0f)
            .collect(Collectors.toList());
    }
}
