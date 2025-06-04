package com.localllm.assistant.controller;

import com.localllm.assistant.controller.dto.IndexRequest;
import com.localllm.assistant.controller.dto.IndexStatusResponse;
import com.localllm.assistant.exception.IndexingException;
import com.localllm.assistant.service.IndexingService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.CompletableFuture;

/**
 * REST controller for indexing operations.
 * Provides endpoints to start and check the status of indexing processes.
 */
@RestController
@RequestMapping("/api/v1/index")
@RequiredArgsConstructor
public class IndexingController {

    private static final Logger log = LoggerFactory.getLogger(IndexingController.class);
    private final IndexingService indexingService;

    /**
     * Starts an indexing process for the specified codebase path.
     *
     * @param request The request containing the codebase path to index
     * @return A CompletableFuture that will complete with the response when indexing starts
     */
    @PostMapping("/start")
    public CompletableFuture<ResponseEntity<IndexStatusResponse>> startIndexing(@RequestBody IndexRequest request) {
        log.info("Received request to start indexing for path: {}", request.getCodebasePath());

        // Validate request
        if (request.getCodebasePath() == null || request.getCodebasePath().isBlank()) {
            return CompletableFuture.completedFuture(ResponseEntity.badRequest()
                .body(IndexStatusResponse.builder().status("FAILED").details("codebasePath is required").build()));
        }

        try {
            // Basic path validation/normalization
            Path basePath = Paths.get(request.getCodebasePath()).toAbsolutePath().normalize();

            return indexingService.startIndexing(basePath)
                .thenApply(v -> ResponseEntity.accepted().body(IndexStatusResponse.builder()
                    .status("STARTED")
                    .details("Indexing process initiated for " + basePath)
                    .build()))
                .exceptionally(ex -> {
                    log.error("Failed to start indexing for path {}: {}", basePath, ex.getMessage());
                    if (ex.getCause() instanceof IndexingException && ex.getMessage().contains("already in progress")) {
                        return ResponseEntity.status(HttpStatus.CONFLICT)
                            .body(IndexStatusResponse.builder().status("FAILED").details(ex.getMessage()).build());
                    }
                    return ResponseEntity.internalServerError()
                        .body(IndexStatusResponse.builder().status("FAILED").details("Internal error: " + ex.getMessage()).build());
                });
        } catch (InvalidPathException e) {
            log.error("Invalid codebase path provided: {}", request.getCodebasePath(), e);
            return CompletableFuture.completedFuture(ResponseEntity.badRequest()
                .body(IndexStatusResponse.builder().status("FAILED").details("Invalid path: " + e.getMessage()).build()));
        }
    }

    /**
     * Gets the current status of the indexing process.
     *
     * @return The current indexing status
     */
    @GetMapping("/status")
    public ResponseEntity<IndexStatusResponse> getStatus() {
        boolean inProgress = indexingService.isIndexingInProgress();
        IndexStatusResponse response = IndexStatusResponse.builder()
            .status(inProgress ? "RUNNING" : "IDLE")
            .details(inProgress ? "Indexing is currently in progress." : "No indexing process is active.")
            .build();
        return ResponseEntity.ok(response);
    }

}
