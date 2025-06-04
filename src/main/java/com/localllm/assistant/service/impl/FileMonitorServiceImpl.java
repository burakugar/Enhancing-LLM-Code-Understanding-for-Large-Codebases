package com.localllm.assistant.service.impl;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.exception.FileMonitorException;
import com.localllm.assistant.service.FileMonitorService;
import com.localllm.assistant.service.UpdateService;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

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
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static java.nio.file.StandardWatchEventKinds.ENTRY_CREATE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_DELETE;
import static java.nio.file.StandardWatchEventKinds.ENTRY_MODIFY;
import static java.nio.file.StandardWatchEventKinds.OVERFLOW;

/**
 * Implementation of FileMonitorService using Java's WatchService API.
 * Monitors a specified codebase directory for changes and notifies the UpdateService.
 */
@Service
public class FileMonitorServiceImpl implements FileMonitorService {

    private static final Logger log = LoggerFactory.getLogger(FileMonitorServiceImpl.class);

    private final UpdateService updateService;

    private final Executor fileMonitorExecutor;

    @Value("${codebase.path:./default_codebase_path}")
    private String codebasePathString;
    private Path codebasePath;

    private WatchService watchService;

    private volatile ExecutorService currentWatchExecutor;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final Map<WatchKey, Path> keys = new HashMap<>();

    public FileMonitorServiceImpl(
        @Lazy UpdateService updateService,
        @Qualifier(AsyncConfig.TASK_EXECUTOR_FILE_MONITOR) Executor fileMonitorExecutor) {
        this.updateService = updateService;
        this.fileMonitorExecutor = fileMonitorExecutor;
    }

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
            startMonitoring();
        } catch (IOException e) {
            log.error("Failed to initialize WatchService for path {}: {}", this.codebasePath, e.getMessage(), e);
            throw new FileMonitorException("Failed to initialize FileMonitorService", e);
        } catch (InvalidPathException e) {
            log.error("Invalid codebase path string configured: {}", codebasePathString, e);
            throw new IllegalArgumentException("Invalid codebase path string: " + codebasePathString, e);
        }
    }

    @Override
    public void startMonitoring() {
        if (!running.compareAndSet(false, true)) {
            log.warn("File monitoring is already running.");
            return;
        }

        if (watchService == null || codebasePath == null) {
            log.error("Cannot start monitoring, service not properly initialized.");
            running.set(false);
            return;
        }

        log.info("Starting file monitoring for directory: {}", codebasePath);
        try {
            registerAll(codebasePath);

            currentWatchExecutor = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "FileWatcherThread");
                t.setDaemon(true);
                return t;
            });

            currentWatchExecutor.submit(this::processEvents);
            log.info("File monitoring task submitted successfully.");
        } catch (IOException e) {
            log.error("Failed to register directory for monitoring: {}", codebasePath, e);
            running.set(false);
            throw new FileMonitorException("Failed to register directory for monitoring: " + codebasePath, e);
        }
    }

    @Override
    public void stopMonitoring() {
        log.info("Stopping file monitoring...");
        if (running.compareAndSet(true, false)) {

            if (currentWatchExecutor != null) {
                currentWatchExecutor.shutdownNow();
                try {
                    if (!currentWatchExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        log.warn("Watch executor did not terminate gracefully.");
                    }
                } catch (InterruptedException e) {
                    log.warn("Interrupted while waiting for watch executor termination.");
                    Thread.currentThread().interrupt();
                }
                currentWatchExecutor = null;
            }

            try {
                if (watchService != null) {
                    watchService.close();
                    log.info("WatchService closed.");
                }
                keys.clear();
            } catch (IOException e) {
                log.error("Error closing WatchService: {}", e.getMessage(), e);
            }
            log.info("File monitoring stopped.");
        } else {
            log.info("File monitoring was not running.");
        }
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    @Override
    public Path getMonitoredPath() {
        return codebasePath;
    }

    @Override
    public synchronized void setMonitoredPathAndRestart(Path newPath) {
        log.info("Attempting to set new monitored path to: {}", newPath);
        Path normalizedNewPath = newPath.toAbsolutePath().normalize();

        if (this.codebasePath != null && this.codebasePath.equals(normalizedNewPath) && isRunning()) {
            log.warn("New path {} is the same as current and monitor is running. No change.", normalizedNewPath);
            return;
        }

        stopMonitoring();

        try {
            this.watchService = FileSystems.getDefault().newWatchService();
            log.info("Initialized new WatchService for path: {}", normalizedNewPath);
        } catch (IOException e) {
            log.error("Failed to initialize new WatchService for path {}: {}", normalizedNewPath, e.getMessage(), e);
            throw new FileMonitorException("Failed to initialize new WatchService for path: " + normalizedNewPath, e);
        }

        this.codebasePath = normalizedNewPath;
        this.codebasePathString = this.codebasePath.toString();

        if (!Files.isDirectory(this.codebasePath)) {
            log.error("New codebase path is not a valid directory: {}", this.codebasePath);
            try {
                if (this.watchService != null) {
                    this.watchService.close();
                }
            } catch (IOException ex) {
                log.warn("Failed to close watchService after invalid path set: {}", ex.getMessage());
            }
            return;
        }

        this.keys.clear();
        this.running.set(false);
        startMonitoring();
        log.info("File monitoring (re)started for new path: {}", this.codebasePath);
    }

    /**
     * Registers the given directory and all its sub-directories with the WatchService.
     */
    private void registerAll(final Path start) throws IOException {
        Files.walkFileTree(start, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
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
                return FileVisitResult.CONTINUE;
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
     * This method runs in a loop on the dedicated watch executor thread.
     */
    void processEvents() {
        log.info("WatchService event processing loop started.");
        while (running.get()) {
            WatchKey key;
            try {
                key = watchService.take();
            } catch (InterruptedException | ClosedWatchServiceException e) {
                log.info("WatchService interrupted or closed, stopping event processing loop.");
                running.set(false);
                Thread.currentThread().interrupt();
                break;
            }

            Path dir = keys.get(key);
            if (dir == null) {
                log.warn("WatchKey not recognized: {}", key);
                continue;
            }

            for (WatchEvent<?> event : key.pollEvents()) {
                WatchEvent.Kind<?> kind = event.kind();

                @SuppressWarnings("unchecked")
                WatchEvent<Path> ev = (WatchEvent<Path>) event;
                Path name = ev.context();
                Path child = dir.resolve(name);

                log.debug("Event kind: {}, File: {}", kind.name(), child);

                if (kind == OVERFLOW) {
                    log.warn("WatchService OVERFLOW event detected for directory: {}. May have missed events.", dir);
                    continue;
                }

                if (!child.toString().endsWith(".java")) {
                    log.trace("Ignoring non-Java file event: {}", child);
                    if (kind == ENTRY_CREATE && Files.isDirectory(child, LinkOption.NOFOLLOW_LINKS)) {
                        try {
                            registerAll(child);
                        } catch (IOException x) {
                            log.error("Failed to register new directory {}: {}", child, x.getMessage());
                        }
                    }
                    continue;
                }

                fileMonitorExecutor.execute(() -> {
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
                });
            }

            boolean valid = key.reset();
            if (!valid) {
                log.warn("WatchKey for directory {} is no longer valid. Removing from monitoring.", dir);
                keys.remove(key);
                if (keys.isEmpty()) {
                    log.warn("No more directories being monitored.");
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
