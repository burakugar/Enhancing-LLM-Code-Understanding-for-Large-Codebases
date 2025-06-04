package com.localllm.assistant.service.impl;

import com.localllm.assistant.llm.LlmClient;
import com.localllm.assistant.model.ModelDto;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@Service
@RequiredArgsConstructor
public class ApplicationConfigService {

    private static final Logger log = LoggerFactory.getLogger(ApplicationConfigService.class);
    private final LlmClient llmClient;

    /**
     * Fetches the list of available LLM models.
     *
     * @return A CompletableFuture containing a list of ModelDto.
     */
    public CompletableFuture<List<ModelDto>> getAvailableLlmModels() {
        log.info("ApplicationConfigService: Fetching available LLM models via LlmClient.");
        return llmClient.getAvailableModelsAsync();
    }

}
