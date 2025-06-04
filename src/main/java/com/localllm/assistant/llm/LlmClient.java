package com.localllm.assistant.llm;

import com.localllm.assistant.model.ModelDto;
import com.localllm.assistant.history.model.ChatMessage;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface LlmClient {

    CompletableFuture<String> generateChatCompletionAsync(
        List<ChatMessage> messages,
        String modelName,
        double temperature,
        int numPredict
    );

    /**
     * Fetches the list of available models (tags) from the Ollama API.
     * @return A CompletableFuture containing a list of ModelDto.
     */
    CompletableFuture<List<ModelDto>> getAvailableModelsAsync();
}
