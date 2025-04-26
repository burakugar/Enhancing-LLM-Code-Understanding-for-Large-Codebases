package com.localllm.assistant.exception;

/**
 * Exception thrown when file system monitoring operations fail.
 */
public class FileMonitorException extends RuntimeException {

    public FileMonitorException(String message) {
        super(message);
    }

    public FileMonitorException(String message, Throwable cause) {
        super(message, cause);
    }
} 