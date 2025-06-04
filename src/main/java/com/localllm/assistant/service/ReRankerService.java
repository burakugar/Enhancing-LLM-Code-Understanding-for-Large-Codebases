package com.localllm.assistant.service;

import com.localllm.assistant.vectorstore.model.VectorSearchResult;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public interface ReRankerService {

    CompletableFuture<List<VectorSearchResult>> reRankAsync(
        String query,
        List<VectorSearchResult> initialResults,
        int topN,
        String rerankerModelName
    );

}
