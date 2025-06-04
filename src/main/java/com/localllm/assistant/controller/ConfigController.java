package com.localllm.assistant.controller;

import com.localllm.assistant.model.ModelDto;
import com.localllm.assistant.service.impl.ApplicationConfigService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/config")
@RequiredArgsConstructor
public class ConfigController {

    private static final Logger log = LoggerFactory.getLogger(ConfigController.class);
    private final ApplicationConfigService applicationConfigService;

    /**
     * Retrieves the list of available LLM models from the Ollama instance.
     *
     * @return A CompletableFuture containing a list of available models.
     */
    @GetMapping("/models")
    public CompletableFuture<ResponseEntity<List<ModelDto>>> getAvailableLlmModels() {
        log.debug("Request received for available LLM models.");
        return applicationConfigService.getAvailableLlmModels()
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> {
                log.error("Error fetching available LLM models: {}", ex.getMessage(), ex);
                // Return an empty list or an error response
                return ResponseEntity.internalServerError().build();
            });
    }

}
