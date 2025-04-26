package com.localllm.assistant.exception;

/**
 * Exception thrown when interactions with LLM services fail.
 */
public class LLMServiceException extends RuntimeException {

    public LLMServiceException(String message) {
        super(message);
    }

    public LLMServiceException(String message, Throwable cause) {
        super(message, cause);
    }
} 