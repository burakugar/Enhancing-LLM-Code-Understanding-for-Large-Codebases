package com.localllm.assistant.service;

import java.nio.file.Path;

/**
 * Service responsible for handling file change events and updating the vector store.
 */
public interface UpdateService {

    /**
     * Handles a detected file change event.
     * This method should trigger parsing, embedding, and vector store updates asynchronously.
     *
     * @param filePath   The absolute path of the file that changed.
     * @param changeType The type of change detected (CREATE, MODIFY, DELETE).
     */
    void handleFileChange(Path filePath, FileMonitorService.ChangeType changeType);
} 