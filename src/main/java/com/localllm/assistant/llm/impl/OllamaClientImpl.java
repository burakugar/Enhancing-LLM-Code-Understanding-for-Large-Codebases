package com.localllm.assistant.llm.impl;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.exception.LlmException;
import com.localllm.assistant.llm.LlmClient;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.ParseException;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.localllm.assistant.config.OllamaConfig;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.MessageRole;

import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of LlmClient that interacts with a local Ollama instance
 * to generate chat completions via HTTP API calls.
 */
@Service
@Slf4j
public class OllamaClientImpl implements LlmClient {

    private static final String CHAT_ENDPOINT = "/api/chat";
    
    private final CloseableHttpClient httpClient;
    private final OllamaConfig ollamaConfig;
    private final ObjectMapper objectMapper;

    /**
     * Constructor with dependency injection.
     *
     * @param httpClient    HTTP client configured for Ollama API
     * @param ollamaConfig  Ollama configuration settings
     * @param objectMapper  JSON object mapper
     */
    public OllamaClientImpl(CloseableHttpClient httpClient, OllamaConfig ollamaConfig, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.ollamaConfig = ollamaConfig;
        this.objectMapper = objectMapper;
        log.info("OllamaClientImpl initialized with config: {}", ollamaConfig);
    }

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<String> generateChatCompletionAsync(List<ChatMessage> messages, String modelName, double temperature) {
        if (messages == null || messages.isEmpty()) {
            return CompletableFuture.failedFuture(new LlmException("No messages provided for chat completion"));
        }

        log.debug("Generating chat completion with model: {}, temperature: {}, message count: {}", 
                modelName, temperature, messages.size());

        CompletableFuture<String> result = new CompletableFuture<>();
        
        try {
            String requestBody = buildChatRequestBody(messages, modelName, temperature);
            String response = executeOllamaRequest(requestBody);
            String generatedText = extractCompletionText(response);
            result.complete(generatedText);
        } catch (Exception e) {
            log.error("Failed to generate chat completion: {}", e.getMessage(), e);
            result.completeExceptionally(new LlmException("Failed to generate chat completion", e));
        }
        
        return result;
    }

    /**
     * Builds the JSON request body for the Ollama chat API.
     *
     * @param messages List of chat messages to process
     * @param modelName The model to use
     * @param temperature The temperature setting
     * @return JSON string for the request body
     * @throws Exception If serialization fails
     */
    private String buildChatRequestBody(List<ChatMessage> messages, String modelName, double temperature) throws Exception {
        ObjectNode requestBody = objectMapper.createObjectNode();
        
        // Set the model name
        requestBody.put("model", modelName != null ? modelName : ollamaConfig.getChatModel());
        
        // Set temperature
        if (temperature >= 0) {
            requestBody.put("temperature", temperature);
        }
        
        // Convert messages to Ollama format
        ArrayNode messagesArray = objectMapper.createArrayNode();
        
        for (ChatMessage message : messages) {
            ObjectNode messageNode = objectMapper.createObjectNode();
            
            // Map our MessageRole to Ollama's expected roles
            String role = convertRole(message.getRole());
            messageNode.put("role", role);
            messageNode.put("content", message.getContent());
            
            messagesArray.add(messageNode);
        }
        
        requestBody.set("messages", messagesArray);
        
        // Add optional options if needed
        // ObjectNode options = objectMapper.createObjectNode();
        // options.put("num_predict", 1024); // max tokens to predict
        // requestBody.set("options", options);
        
        return objectMapper.writeValueAsString(requestBody);
    }

    /**
     * Converts our internal MessageRole enum to Ollama API expected role string.
     *
     * @param role Our internal MessageRole
     * @return String role for Ollama API
     */
    private String convertRole(MessageRole role) {
        // Ollama typically expects: "system", "user", or "assistant"
        switch (role) {
            case SYSTEM:
                return "system";
            case USER:
                return "user";
            case ASSISTANT:
                return "assistant";
            case CONTEXT:
                // Convert context to system message, or handle specially
                return "system";
            default:
                log.warn("Unknown role type: {}, defaulting to 'user'", role);
                return "user";
        }
    }

    /**
     * Executes the HTTP request to the Ollama API.
     *
     * @param requestBody JSON request body
     * @return Response JSON as string
     * @throws IOException If request execution fails
     * @throws LlmException If Ollama returns an error
     */
    private String executeOllamaRequest(String requestBody) throws IOException, LlmException, ParseException {
        String url = ollamaConfig.getBaseUrl() + CHAT_ENDPOINT;
        HttpPost httpPost = new HttpPost(url);
        
        StringEntity entity = new StringEntity(requestBody, ContentType.APPLICATION_JSON);
        httpPost.setEntity(entity);
        
        try (CloseableHttpResponse response = httpClient.execute(httpPost)) {
            int statusCode = response.getCode();
            String responseBody = EntityUtils.toString(response.getEntity());
            
            if (statusCode != 200) {
                log.error("Ollama API returned non-200 status code: {} - {}", statusCode, responseBody);
                throw new LlmException("Ollama API returned error status code: " + statusCode);
            }
            
            return responseBody;
        }
    }

    /**
     * Extracts the generated text from the Ollama API response.
     *
     * @param responseJson Response JSON string
     * @return The generated text
     * @throws Exception If parsing fails
     */
    private String extractCompletionText(String responseJson) throws Exception {
        JsonNode rootNode = objectMapper.readTree(responseJson);
        
        // Check for errors in the response
        if (rootNode.has("error")) {
            String errorMessage = rootNode.get("error").asText();
            log.error("Ollama API returned error: {}", errorMessage);
            throw new LlmException("Ollama API error: " + errorMessage);
        }
        
        // Extract the message content
        if (rootNode.has("message")) {
            JsonNode messageNode = rootNode.get("message");
            if (messageNode.has("content")) {
                return messageNode.get("content").asText();
            }
        }
        
        log.error("Unexpected Ollama API response structure: {}", responseJson);
        throw new LlmException("Unable to extract response content from Ollama API response");
    }
} 
