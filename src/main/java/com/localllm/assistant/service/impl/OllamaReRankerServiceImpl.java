package com.localllm.assistant.service.impl;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.llm.LlmClient;
import com.localllm.assistant.service.ReRankerService;
import com.localllm.assistant.vectorstore.model.VectorSearchResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "reranker.enabled", havingValue = "true")
@RequiredArgsConstructor
public class OllamaReRankerServiceImpl implements ReRankerService {

    private static final Logger log = LoggerFactory.getLogger(OllamaReRankerServiceImpl.class);
    private final LlmClient llmClient;
    private final ObjectMapper objectMapper;

    @Value("${reranker.model}")
    private String defaultRerankerModel;
    @Value("${reranker.temperature}")
    private double rerankerTemp;
    @Value("${reranker.defaultTopN}")
    private int defaultTopNThreshold;

    @Override
    public CompletableFuture<List<VectorSearchResult>> reRankAsync(
        String query,
        List<VectorSearchResult> initialResults,
        int requestedTopN,
        String rerankerModelName
    ) {
        if (initialResults == null || initialResults.isEmpty()) {
            log.debug("Initial results for re-ranking are empty. Query: {}", query);
            return CompletableFuture.completedFuture(List.of());
        }

        int topN = Math.min(requestedTopN, this.defaultTopNThreshold);
        String modelToUse = StringUtils.hasText(rerankerModelName) ? rerankerModelName : this.defaultRerankerModel;

        log.debug("Re-ranking with model: {}, query: '{}', initial results: {}, aiming for topN: {}",
            modelToUse, query, initialResults.size(), topN);

        StringBuilder sb = new StringBuilder();
        sb.append("Re-rank these code snippets by relevance to:\n\"")
            .append(query.replace("\"", "\\\""))
            .append("\"\n\n");
        for (int i = 0; i < initialResults.size(); i++) {
            VectorSearchResult r = initialResults.get(i);
            sb.append(i).append(". score=").append(String.format("%.3f", r.getScore())).append("\n")
                .append("```java\n")
                .append(r.getSegment().getContent() != null ? r.getSegment().getContent() : "") // Handle null content
                .append("\n```\n\n");
        }
        sb.append("Return ONLY a JSON array of the original indices, ordered most → least relevant. E.g. [2,0,1]");

        List<ChatMessage> messages = List.of(
            ChatMessage.builder()
                .role(MessageRole.SYSTEM)
                .content("You are a precise code reranker. Output ONLY a JSON array of indices.")
                .build(),
            ChatMessage.builder()
                .role(MessageRole.USER)
                .content(sb.toString())
                .build()
        );

        return llmClient.generateChatCompletionAsync(
                messages,
                modelToUse,
                rerankerTemp,
                256
            )
            .thenApply(responseText -> {
                List<Integer> order;
                try {
                    log.debug("Reranker LLM response: {}", responseText);
                    order = objectMapper.readValue(responseText.trim(), new TypeReference<List<Integer>>() {
                    });
                } catch (Exception e) {
                    log.error("Failed to parse reranker JSON response: '{}'. Error: {}", responseText, e.getMessage(), e);
                    return initialResults.stream().limit(topN).collect(Collectors.toList());
                }
                return order.stream()
                    .filter(idx -> idx >= 0 && idx < initialResults.size())
                    .map(initialResults::get)
                    .limit(topN)
                    .collect(Collectors.toList());
            }).exceptionally(ex -> {
                log.error("Error during re-ranking LLM call for query '{}': {}", query, ex.getMessage(), ex);
                return initialResults.stream().limit(topN).collect(Collectors.toList());
            });
    }
}
