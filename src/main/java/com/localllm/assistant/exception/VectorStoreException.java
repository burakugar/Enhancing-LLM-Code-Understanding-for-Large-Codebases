package com.localllm.assistant.exception;

/**
 * Exception thrown when operations related to vector store fail.
 */
public class VectorStoreException extends RuntimeException {

    public VectorStoreException(String message) {
        super(message);
    }

    public VectorStoreException(String message, Throwable cause) {
        super(message, cause);
    }
} 