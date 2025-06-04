package com.localllm.assistant.controller.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryRequest {
    private String query;
    private String conversationId;
    private Double temperature;
    private Integer maxContextSegments;
    private Double minSimilarityScore;
    private Boolean useReRanker;
    private Integer reRankerTopN;
    private Integer llmMaxNewTokens;
    private String modelName;
    private String rerankerModelName;
}
