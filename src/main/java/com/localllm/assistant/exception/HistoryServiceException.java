package com.localllm.assistant.exception;

/**
 * Exception thrown when there is an error in the history service.
 */
public class HistoryServiceException extends RuntimeException {

    /**
     * Constructs a new history service exception with the specified detail message.
     *
     * @param message the detail message
     */
    public HistoryServiceException(String message) {
        super(message);
    }

    /**
     * Constructs a new history service exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause the cause
     */
    public HistoryServiceException(String message, Throwable cause) {
        super(message, cause);
    }
} 