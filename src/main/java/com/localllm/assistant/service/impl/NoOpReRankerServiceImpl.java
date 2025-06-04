package com.localllm.assistant.service.impl;

import com.localllm.assistant.service.ReRankerService;
import com.localllm.assistant.vectorstore.model.VectorSearchResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;


import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

@Service
@ConditionalOnProperty(name = "reranker.enabled", havingValue = "false", matchIfMissing = true)
public class NoOpReRankerServiceImpl implements ReRankerService {

    private static final Logger log = LoggerFactory.getLogger(NoOpReRankerServiceImpl.class);

    @Override
    public CompletableFuture<List<VectorSearchResult>> reRankAsync(
        String query,
        List<VectorSearchResult> initialResults,
        int topN,
        String rerankerModelName
    ) {
        log.debug("No-Op ReRanker: Returning top {} initial results without actual re-ranking. Reranker model name '{}' ignored.",
            topN, StringUtils.hasText(rerankerModelName) ? rerankerModelName : "N/A");

        if (initialResults == null) {
            return CompletableFuture.completedFuture(List.of());
        }

        List<VectorSearchResult> sortedAndTrimmed = initialResults.stream()
            .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
            .limit(topN)
            .collect(Collectors.toList());
        return CompletableFuture.completedFuture(sortedAndTrimmed);
    }
}
