package com.localllm.assistant.exception;

/**
 * Exception thrown when embedding generation fails.
 */
public class EmbeddingException extends RuntimeException {

    public EmbeddingException(String message) {
        super(message);
    }

    public EmbeddingException(String message, Throwable cause) {
        super(message, cause);
    }
} 