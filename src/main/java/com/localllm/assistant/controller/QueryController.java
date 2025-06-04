package com.localllm.assistant.controller;

import com.localllm.assistant.controller.dto.QueryRequest;
import com.localllm.assistant.controller.dto.QueryResponse;
import com.localllm.assistant.service.QueryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;

@RestController
@RequestMapping("/api/v1/query")
@RequiredArgsConstructor
public class QueryController {

    private static final Logger log = LoggerFactory.getLogger(QueryController.class);
    private final QueryService queryService;

    @PostMapping
    public CompletableFuture<ResponseEntity<QueryResponse>> handleQuery(@RequestBody QueryRequest request) {
        log.info("Received query request: {}", request);

        if (request.getQuery() == null || request.getQuery().isBlank()) {
            QueryResponse errorResponse = QueryResponse.builder()
                .answer("Query cannot be empty.")
                .conversationId(request.getConversationId())
                .sources(Collections.emptyList())
                .build();
            return CompletableFuture.completedFuture(ResponseEntity.badRequest().body(errorResponse));
        }

        return queryService.processQuery(request)
            .thenApply(ResponseEntity::ok)
            .exceptionally(ex -> {
                log.error("Error processing query: {}", ex.getMessage(), ex);
                // Construct a proper error QueryResponse
                QueryResponse errorResponse = QueryResponse.builder()
                    .answer("An internal error occurred: " + ex.getMessage())
                    .conversationId(request.getConversationId())
                    .sources(Collections.emptyList())
                    .build();
                return ResponseEntity.internalServerError().body(errorResponse);
            });
    }
}
