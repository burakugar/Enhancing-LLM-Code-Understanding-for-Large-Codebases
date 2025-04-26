package com.localllm.assistant.exception;

/**
 * Exception thrown when code parsing fails.
 */
public class ParsingException extends RuntimeException {

    /**
     * Creates a new ParsingException with the given message.
     *
     * @param message the detail message
     */
    public ParsingException(String message) {
        super(message);
    }

    /**
     * Creates a new ParsingException with the given message and cause.
     *
     * @param message the detail message
     * @param cause the cause of this exception
     */
    public ParsingException(String message, Throwable cause) {
        super(message, cause);
    }
} 