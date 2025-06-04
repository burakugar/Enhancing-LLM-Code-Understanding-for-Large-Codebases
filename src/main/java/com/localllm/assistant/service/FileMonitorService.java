package com.localllm.assistant.service;

import java.nio.file.Path;

/**
 * Service interface for monitoring filesystem changes in the codebase directory.
 */
public interface FileMonitorService {

    /**
     * Enum representing the type of file change detected.
     */
    enum ChangeType {
        CREATE,
        MODIFY,
        DELETE
    }

    /**
     * Starts the file monitoring process.
     * Should be idempotent (calling start on a running service does nothing).
     */
    void startMonitoring();

    /**
     * Stops the file monitoring process.
     * Should clean up resources like the WatchService.
     */
    void stopMonitoring();

    /**
     * Checks if the monitoring service is currently active.
     *
     * @return true if monitoring, false otherwise.
     */
    boolean isRunning();

    /**
     * Gets the root path being monitored.
     *
     * @return The absolute Path of the monitored codebase directory.
     */
    Path getMonitoredPath();

    /**
     * Sets a new path to monitor and restarts the monitoring service.
     * If the new path is the same as the current and the service is running, no action is taken.
     * This method will stop any current monitoring, update the path, and start monitoring the new path.
     *
     * @param newPath The new absolute path to monitor.
     */
    void setMonitoredPathAndRestart(Path newPath);
} 
