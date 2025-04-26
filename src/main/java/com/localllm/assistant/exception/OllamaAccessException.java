package com.localllm.assistant.exception;

/**
 * Exception thrown when access to Ollama APIs fails.
 */
public class OllamaAccessException extends RuntimeException {

    public OllamaAccessException(String message) {
        super(message);
    }

    public OllamaAccessException(String message, Throwable cause) {
        super(message, cause);
    }
} 