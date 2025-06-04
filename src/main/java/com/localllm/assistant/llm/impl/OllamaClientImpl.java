package com.localllm.assistant.llm.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.OllamaConfig;
import com.localllm.assistant.exception.LlmException;
import com.localllm.assistant.exception.OllamaAccessException;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.llm.LlmClient;
import com.localllm.assistant.model.ModelDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.async.methods.SimpleHttpRequest;
import org.apache.hc.client5.http.async.methods.SimpleHttpResponse;
import org.apache.hc.client5.http.async.methods.SimpleRequestBuilder;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.concurrent.FutureCallback;
import org.apache.hc.core5.http.ContentType;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

@Service
@Slf4j
public class OllamaClientImpl implements LlmClient {

    private static final String CHAT_ENDPOINT = "/api/chat";
    private static final String TAGS_ENDPOINT = "/api/tags";

    private final CloseableHttpAsyncClient httpAsyncClient;
    private final OllamaConfig ollamaConfig;
    private final ObjectMapper objectMapper;
    private final URI chatUri;
    private final URI tagsUri;

    public OllamaClientImpl(
        @Qualifier("sharedHttpAsyncClient") CloseableHttpAsyncClient httpAsyncClient,
        OllamaConfig ollamaConfig,
        ObjectMapper objectMapper) {
        this.httpAsyncClient = httpAsyncClient;
        this.ollamaConfig = ollamaConfig;
        this.objectMapper = objectMapper;
        try {
            this.chatUri = new URI(ollamaConfig.getBaseUrl() + CHAT_ENDPOINT);
            this.tagsUri = new URI(ollamaConfig.getBaseUrl() + TAGS_ENDPOINT); // Initialize tagsUri
        } catch (URISyntaxException e) {
            log.error("Invalid Ollama base URL: {}", ollamaConfig.getBaseUrl(), e);
            throw new LlmException("Invalid Ollama base URL configuration", e);
        }
        log.info("OllamaClientImpl initialized with config: {}", ollamaConfig);
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<String> generateChatCompletionAsync(List<ChatMessage> messages, String modelName, double temperature, int numPredict) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.failedFuture(new LlmException("No messages provided for chat completion"));
        }

        int effectiveNumPredict = (numPredict > 0) ? numPredict : ollamaConfig.getChatModelDefaultNumPredict();

        log.debug("Generating chat completion with model: {}, temperature: {}, num_predict: {}, message count: {}",
            modelName, temperature, effectiveNumPredict, messages.size());

        CompletableFuture<String> future = new CompletableFuture<>();

        try {
            String requestBodyJson = buildChatRequestBody(messages, modelName, temperature, effectiveNumPredict);
            if (log.isTraceEnabled()) {
                log.trace("Ollama chat request body: {}", requestBodyJson);
            } else {
                log.debug("Ollama chat request body (first 500 chars): {}", requestBodyJson.substring(0, Math.min(requestBodyJson.length(), 500)));
            }

            SimpleHttpRequest request = SimpleRequestBuilder.post(chatUri)
                .setBody(requestBodyJson, ContentType.APPLICATION_JSON)
                .build();

            httpAsyncClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
                @Override
                public void completed(SimpleHttpResponse response) {
                    try {
                        String responseBody = response.getBodyText();
                        int statusCode = response.getCode();
                        if (log.isTraceEnabled()) {
                            log.trace("Ollama chat response status code: {}, body: {}", statusCode, responseBody);
                        } else {
                            log.debug("Ollama chat response status code: {}, body (first 500 chars): {}", statusCode,
                                responseBody.substring(0, Math.min(responseBody.length(), 500)));
                        }

                        if (statusCode != 200) {
                            log.error("Ollama Chat API Error: Status Code {}, Body: {}", statusCode, responseBody);
                            future.completeExceptionally(
                                new LlmException("Ollama Chat API returned non-200 status code: " + statusCode + " Body: " + responseBody));
                            return;
                        }

                        String generatedText = extractCompletionText(responseBody);
                        future.complete(generatedText);

                    } catch (Exception e) {
                        log.error("Failed to process Ollama chat response: {}", e.getMessage(), e);
                        future.completeExceptionally(new LlmException("Failed to process Ollama chat response", e));
                    }
                }

                @Override
                public void failed(Exception ex) {
                    log.error("Ollama chat request failed: {}", ex.getMessage(), ex);
                    future.completeExceptionally(new LlmException("Ollama chat request failed", ex));
                }

                @Override
                public void cancelled() {
                    log.warn("Ollama chat request cancelled");
                    future.completeExceptionally(new CancellationException("Ollama chat request cancelled."));
                }
            });

        } catch (Exception e) {
            log.error("Unexpected error preparing Ollama chat request", e);
            future.completeExceptionally(new LlmException("Unexpected error preparing Ollama chat request", e));
        }

        return future;
    }

    private String buildChatRequestBody(List<ChatMessage> messages, String modelName, double temperature, int numPredict) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();

        requestBody.put("model", modelName != null ? modelName : ollamaConfig.getChatModel());
        requestBody.put("stream", false);

        ArrayNode messagesArray = objectMapper.createArrayNode();
        for (ChatMessage message : messages) {
            ObjectNode messageNode = objectMapper.createObjectNode();
            String role = convertRole(message.getRole());
            messageNode.put("role", role);
            messageNode.put("content", message.getContent());
            messagesArray.add(messageNode);
        }
        requestBody.set("messages", messagesArray);

        ObjectNode options = objectMapper.createObjectNode();
        if (temperature >= 0) {
            options.put("temperature", temperature);
        }
        options.put("num_predict", numPredict);
        requestBody.set("options", options);

        return objectMapper.writeValueAsString(requestBody);
    }

    private String convertRole(MessageRole role) {
        switch (role) {
            case SYSTEM:
                return "system";
            case USER:
                return "user";
            case ASSISTANT:
                return "assistant";
            case CONTEXT:
                log.warn("Converting CONTEXT role to 'system'. Review if this is optimal for the model.");
                return "system";
            default:
                log.warn("Unknown role type: {}, defaulting to 'user'", role);
                return "user";
        }
    }

    private String extractCompletionText(String responseJson) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseJson);

        if (rootNode.has("error")) {
            String errorMessage = rootNode.get("error").asText();
            log.error("Ollama API returned error in response body: {}", errorMessage);
            throw new LlmException("Ollama API error: " + errorMessage);
        }

        JsonNode messageNode = rootNode.path("message");
        if (!messageNode.isMissingNode() && messageNode.has("content")) {
            return messageNode.get("content").asText();
        }

        log.error("Unexpected Ollama API response structure (non-streaming). Missing 'message.content'. Response: {}", responseJson);
        throw new LlmException("Unable to extract response content from Ollama API response. Full response: " + responseJson);
    }

    /**
     * Fetches the list of available models (tags) from the Ollama API.
     *
     * @return A CompletableFuture containing a list of ModelDto.
     */
    @Async(AsyncConfig.TASK_EXECUTOR_VECTOR_STORE)
    public CompletableFuture<List<ModelDto>> getAvailableModelsAsync() {
        log.debug("Fetching available models from Ollama API: {}", tagsUri);
        CompletableFuture<List<ModelDto>> future = new CompletableFuture<>();
        SimpleHttpRequest request = SimpleRequestBuilder.get(tagsUri).build();

        httpAsyncClient.execute(request, new FutureCallback<SimpleHttpResponse>() {
            @Override
            public void completed(SimpleHttpResponse response) {
                try {
                    String responseBody = response.getBodyText();
                    int statusCode = response.getCode();
                    log.debug("Ollama /api/tags response status: {}, body (first 500 chars): {}", statusCode,
                        responseBody.substring(0, Math.min(responseBody.length(), 500)));

                    if (statusCode != 200) {
                        log.error("Ollama /api/tags Error: Status Code {}, Body: {}", statusCode, responseBody);
                        future.completeExceptionally(
                            new OllamaAccessException("Ollama /api/tags returned non-200 status: " + statusCode + " Body: " + responseBody));
                        return;
                    }

                    JsonNode rootNode = objectMapper.readTree(responseBody);
                    List<ModelDto> models = new ArrayList<>();
                    if (rootNode.has("models") && rootNode.get("models").isArray()) {
                        for (JsonNode modelNode : rootNode.get("models")) {
                            String modelId = modelNode.path("name").asText(); // e.g., "mistral:latest"
                            String displayName = modelId;

                            if (!modelId.isEmpty()) {
                                models.add(ModelDto.builder().id(modelId).name(displayName).build());
                            }
                        }
                    }
                    future.complete(models);
                } catch (Exception e) {
                    log.error("Failed to process Ollama /api/tags response: {}", e.getMessage(), e);
                    future.completeExceptionally(new OllamaAccessException("Failed to process Ollama /api/tags response", e));
                }
            }

            @Override
            public void failed(Exception ex) {
                log.error("Ollama /api/tags request failed: {}", ex.getMessage(), ex);
                future.completeExceptionally(new OllamaAccessException("Ollama /api/tags request failed", ex));
            }

            @Override
            public void cancelled() {
                log.warn("Ollama /api/tags request cancelled");
                future.completeExceptionally(new CancellationException("Ollama /api/tags request cancelled."));
            }
        });
        return future;
    }
}
