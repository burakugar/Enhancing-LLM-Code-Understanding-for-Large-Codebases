package com.localllm.assistant.vectorstore.model;

import java.util.List;
import java.util.Map;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents an entry to be stored in or retrieved from the vector database.
 * Typically includes the embedding vector, the original content (or reference), and metadata.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class VectorEntry {

    /**
     * Unique identifier for this entry, usually matching the corresponding CodeSegment ID.
     */
    private String id;

    /**
     * The dense vector embedding representing the code segment's semantics.
     * Represented as a list of floats.
     */
    private List<Float> embedding;

    /**
     * Metadata associated with this vector embedding.
     * Should contain searchable fields like filePath, startLine, endLine, segmentType, entityName.
     * This structure needs to align with what ChromaDB expects for its metadata filtering.
     * Example: {"filePath": "path/to/file.java", "startLine": 10, "type": "METHOD"}
     */
    private Map<String, Object> metadata;

    /**
     * Optional: The original text content associated with the embedding.
     * ChromaDB calls this 'document'. Including it can simplify retrieval
     * if the DB returns it directly, otherwise, it might be omitted to save space
     * if the content can be retrieved via the ID from another source (e.g., CodeSegment cache).
     */
    private String document; // Corresponds to ChromaDB's 'document'
} 