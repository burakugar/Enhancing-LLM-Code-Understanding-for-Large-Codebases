package com.localllm.assistant.exception;

/**
 * Exception thrown for failures related to interactions with the LLM service (Ollama).
 * This includes connection issues, invalid responses, or other errors encountered
 * when generating chat completions.
 */
public class LlmException extends RuntimeException {

    /**
     * Constructs a new LLM exception with the specified detail message.
     *
     * @param message The detail message (which is saved for later retrieval by the getMessage() method).
     */
    public LlmException(String message) {
        super(message);
    }

    /**
     * Constructs a new LLM exception with the specified detail message and cause.
     *
     * @param message The detail message (which is saved for later retrieval by the getMessage() method).
     * @param cause The cause (which is saved for later retrieval by the getCause() method).
     */
    public LlmException(String message, Throwable cause) {
        super(message, cause);
    }
} 