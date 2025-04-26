package com.localllm.assistant.service.impl;

import static java.nio.file.StandardWatchEventKinds.*;

import java.io.IOException;
import java.nio.file.ClosedWatchServiceException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.exception.FileMonitorException;
import com.localllm.assistant.service.FileMonitorService;
import com.localllm.assistant.service.UpdateService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;

/**
 * Implementation of FileMonitorService using Java's WatchService API.
 * Monitors a specified codebase directory for changes and notifies the UpdateService.
 */
@Service
@RequiredArgsConstructor
public class FileMonitorServiceImpl implements FileMonitorService {

    private static final Logger log = LoggerFactory.getLogger(FileMonitorServiceImpl.class);

    // Lazily inject UpdateService to avoid circular dependency if UpdateService depends on this
    @Lazy
    private final UpdateService updateService;

    // Inject codebase path from application properties (e.g., codebase.path=...)
    // Provide a default value or ensure it's set
    @Value("${codebase.path:./default_codebase_path}")
    private String codebasePathString;
    private Path codebasePath;

    private WatchService watchService;
    // Use a single dedicated thread for the WatchService loop
    private final ExecutorService watchExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FileWatcherThread");
        t.setDaemon(true); // Allow JVM to exit even if this thread is running
        return t;
    });
    private volatile boolean running = false;
    private final Map<WatchKey, Path> keys = new HashMap<>();

    @PostConstruct
    public void initialize() {
        try {
            this.codebasePath = Paths.get(codebasePathString).toAbsolutePath().normalize();
            if (!Files.isDirectory(this.codebasePath)) {
                log.error("Configured codebase path is not a valid directory: {}", this.codebasePath);
                throw new IllegalArgumentException("Invalid codebase path: " + this.codebasePathString);
            }
            this.watchService = FileSystems.getDefault().newWatchService();
            log.info("Initialized FileMonitorService. Base path: {}", this.codebasePath);
            // Start monitoring in PostConstruct or via a separate start method
            startMonitoring();
        } catch (IOException e) {
            log.error("Failed to initialize WatchService for path {}: {}", this.codebasePath, e.getMessage(), e);
            // Decide how to handle initialization failure - maybe prevent app startup?
            throw new FileMonitorException("Failed to initialize FileMonitorService", e);
        } catch (InvalidPathException e) {
            log.error("Invalid codebase path string configured: {}", codebasePathString, e);
            throw new IllegalArgumentException("Invalid codebase path string: " + codebasePathString, e);
        }
    }

    @Override
    public void startMonitoring() {
        if (running) {
            log.warn("File monitoring is already running.");
            return;
        }
        if (watchService == null || codebasePath == null) {
            log.error("Cannot start monitoring, service not properly initialized.");
            return;
        }

        log.info("Starting file monitoring for directory: {}", codebasePath);
        try {
            registerAll(codebasePath);
            running = true;
            // Submit the watching task to the dedicated executor
            watchExecutor.submit(this::processEvents);
            log.info("File monitoring task submitted successfully.");
        } catch (IOException e) {
            log.error("Failed to register directory for monitoring: {}", codebasePath, e);
            running = false; // Ensure state is correct
            throw new FileMonitorException("Failed to register directory for monitoring: " + codebasePath, e);
        }
    }

    @Override
    public void stopMonitoring() {
        log.info("Stopping file monitoring...");
        running = false;
        watchExecutor.shutdownNow(); // Interrupt the watching thread
        try {
            if (!watchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("Watch executor did not terminate gracefully.");
            }
            if (watchService != null) {
                watchService.close();
                log.info("WatchService closed.");
            }
            keys.clear();
        } catch (IOException e) {
            log.error("Error closing WatchService: {}", e.getMessage(), e);
        } catch (InterruptedException e) {
            log.warn("Interrupted while waiting for watch executor termination.");
            Thread.currentThread().interrupt();
        }
        log.info("File monitoring stopped.");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public Path getMonitoredPath() {
        return codebasePath;
    }

    /**
     * Registers the given directory and all its sub-directories with the WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        // Register directory and sub-directories
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                // Skip hidden directories or specific unwanted directories (e.g., .git, target, build)
                if (Files.isHidden(dir) || dir.getFileName().toString().equals(".git") ||
                    dir.getFileName().toString().equals("target") || dir.getFileName().toString().equals("build")) {
                    log.debug("Skipping directory: {}", dir);
                    return FileVisitResult.SKIP_SUBTREE;
                }
                registerDirectory(dir);
                return FileVisitResult.CONTINUE;
            }
            @Override
            public FileVisitResult visitFileFailed(Path file, IOException exc) throws IOException {
                log.warn("Failed to access path during registration: {} - {}", file, exc.getMessage());
                return FileVisitResult.CONTINUE; // Continue walking even if one path fails
            }
        });
        log.info("Finished registering directories. Total keys: {}", keys.size());
    }

    /**
     * Register the given directory with the WatchService
     */
    private void registerDirectory(Path dir) throws IOException {
        WatchKey key = dir.register(watchService, ENTRY_CREATE, ENTRY_DELETE, ENTRY_MODIFY);
        keys.put(key, dir);
        log.trace("Registered directory for monitoring: {}", dir);
    }

    /**
     * Process all events for keys queued to the watcher.
     * This method runs in a loop on the dedicated watchExecutor thread.
     */
    @Async(AsyncConfig.TASK_EXECUTOR_FILE_MONITOR)
    void processEvents() {
        log.info("WatchService event processing loop started.");
        while (running) {
            WatchKey key;
            try {
                // Wait indefinitely for a key to be signaled
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                // Exit loop if interrupted or service is closed
                log.info("WatchService interrupted or closed, stopping event processing loop.");
                running = false; // Ensure loop condition is updated
                Thread.currentThread().interrupt();
                break;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                log.warn("WatchKey not recognized: {}", key);
                continue;
            }

            // Process all events for the current key
            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                // Context for directory entry event is the file name of entry
                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);

                log.debug("Event kind: {}, File: {}", kind.name(), child);

                // Handle specific events
                if (kind == OVERFLOW) {
                    log.warn("WatchService OVERFLOW event detected for directory: {}. May have missed events.", dir);
                    continue; // Skip processing specific file, might need full rescan logic
                }

                // We only care about Java files for parsing/embedding
                // Adjust filter as needed (e.g., support other languages, config files)
                if (!child.toString().endsWith(".java")) {
                    log.trace("Ignoring non-Java file event: {}", child);
                    // If a directory is created, register it for watching
                    if (kind == ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            registerAll(child); // Register new directory and subdirectories
                        } catch (IOException x) {
                            log.error("Failed to register new directory {}: {}", child, x.getMessage());
                        }
                    }
                    continue;
                }

                // Notify the UpdateService asynchronously
                if (kind == ENTRY_CREATE) {
                    log.info("Detected CREATE event for: {}", child);
                    updateService.handleFileChange(child, FileMonitorService.ChangeType.CREATE);
                }
                if (kind == ENTRY_MODIFY) {
                    log.info("Detected MODIFY event for: {}", child);
                    updateService.handleFileChange(child, FileMonitorService.ChangeType.MODIFY);
                }
                if (kind == ENTRY_DELETE) {
                    log.info("Detected DELETE event for: {}", child);
                    updateService.handleFileChange(child, FileMonitorService.ChangeType.DELETE);
                }
            }

            // Reset the key -- this step is critical! If the key is no longer valid,
            // the directory is inaccessible so remove it from the keys map.
            boolean valid = key.reset();
            if (!valid) {
                log.warn("WatchKey for directory {} is no longer valid. Removing from monitoring.", dir);
                keys.remove(key);
                // If the directory is deleted, stop watching
                if (keys.isEmpty()) {
                    log.warn("No more directories being monitored. Stopping service?");
                    // Decide if monitoring should stop entirely
                    // running = false; // Uncomment to stop if all keys invalid
                }
            }
        }
        log.info("WatchService event processing loop finished.");
    }

    @PreDestroy
    public void cleanup() {
        stopMonitoring();
    }
} 
