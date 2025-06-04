package com.localllm.assistant.vectorstore.model;

import com.localllm.assistant.parser.model.CodeSegment;
import lombok.Builder;
import lombok.Getter;
import lombok.ToString;

/**
 * Represents a search result from a vector store, including the segment and its similarity score.
 */
@Getter
@Builder(toBuilder = true)
@ToString
public class VectorSearchResult {
    private final CodeSegment segment;
    private final double score; // Higher score means more similar (typically 0.0 to 1.0)

    /**
     * Creates a new vector search result.
     *
     * @param segment The code segment found in the search
     * @param score   The similarity score (e.g., 1.0 for perfect match, 0.0 for no similarity)
     */
    public VectorSearchResult(CodeSegment segment, double score) {
        this.segment = segment;
        this.score = score;
    }
}