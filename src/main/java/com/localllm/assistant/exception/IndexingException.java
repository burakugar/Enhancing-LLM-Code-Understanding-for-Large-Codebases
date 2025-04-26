package com.localllm.assistant.exception;

/**
 * Exception thrown when the indexing process fails.
 */
public class IndexingException extends RuntimeException {

    public IndexingException(String message) {
        super(message);
    }

    public IndexingException(String message, Throwable cause) {
        super(message, cause);
    }
} 