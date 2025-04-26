package com.localllm.assistant.vectorstore;

import com.localllm.assistant.parser.model.CodeSegment;

/**
 * Represents a search result from a vector store with similarity score.
 */
public class VectorSearchResult {
    private final CodeSegment segment;
    private final double score;

    /**
     * Creates a new vector search result.
     *
     * @param segment The code segment found in the search
     * @param score   The similarity score (from 0.0 to 1.0, higher is more similar)
     */
    public VectorSearchResult(CodeSegment segment, double score) {
        this.segment = segment;
        this.score = score;
    }

    /**
     * Gets the code segment from the search result.
     *
     * @return The code segment
     */
    public CodeSegment getSegment() {
        return segment;
    }

    /**
     * Gets the similarity score of this result.
     *
     * @return The similarity score (from 0.0 to 1.0)
     */
    public double getScore() {
        return score;
    }

    @Override
    public String toString() {
        return "VectorSearchResult{" +
                "segment=" + segment +
                ", score=" + score +
                '}';
    }
} 