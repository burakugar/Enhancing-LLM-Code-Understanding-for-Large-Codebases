package com.localllm.assistant.llm;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import com.localllm.assistant.history.model.ChatMessage;

/**
 * Interface for interacting with the local Large Language Model (LLM) service (via Ollama)
 * to generate responses based on provided context and history.
 */
public interface LlmClient {

    /**
     * Generates a chat completion response from the LLM asynchronously based on a list of messages.
     * The list typically includes system prompts, previous user/assistant turns, and current user query.
     *
     * @param messages A list of ChatMessage objects representing the conversation history and current prompt.
     *                 The implementation needs to format this list according to the Ollama API requirements.
     * @param modelName The specific Ollama model to use for this request (e.g., "llama3:8b-instruct").
     * @param temperature The sampling temperature (e.g., 0.7). Lower values are more deterministic.
     * @return A CompletableFuture containing the String response content from the LLM.
     *         Completes exceptionally with LlmException if the generation fails.
     */
    CompletableFuture<String> generateChatCompletionAsync(
            List<ChatMessage> messages,
            String modelName,
            double temperature
    );

    // Optional: Add methods for streaming responses if needed later.
    // default CompletableFuture<Void> generateChatCompletionStreamAsync(...) { ... }
} 