package com.localllm.assistant.config;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Configuration for asynchronous task execution using ThreadPoolTaskExecutor.
 * Defines specific executors for different types of background tasks.
 */
@Configuration
// Implementing AsyncConfigurer allows customizing the default executor and exception handler
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String TASK_EXECUTOR_INDEXING = "taskExecutorIndexing";
    public static final String TASK_EXECUTOR_FILE_MONITOR = "taskExecutorFileMonitor";
    public static final String TASK_EXECUTOR_EMBEDDING = "taskExecutorEmbedding";
    public static final String TASK_EXECUTOR_VECTOR_STORE = "taskExecutorVectorStore";

    /**
     * Defines a thread pool executor specifically for long-running indexing tasks.
     * Uses a smaller core pool size to avoid consuming too many resources constantly.
     *
     * @return Configured ThreadPoolTaskExecutor for indexing.
     */
    @Bean(name = TASK_EXECUTOR_INDEXING)
    public Executor taskExecutorIndexing() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Start with 1 thread, allow up to 2 concurrent indexing tasks (adjust as needed)
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        // Queue capacity for pending tasks if all threads are busy
        executor.setQueueCapacity(10);
        executor.setThreadNamePrefix("IndexingThread-");
        // Allow core threads to time out if idle (optional)
        // executor.setAllowCoreThreadTimeOut(true);
        // executor.setKeepAliveSeconds(60);
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
                 TASK_EXECUTOR_INDEXING, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * Defines a thread pool executor for file monitoring and quick update tasks.
     * Uses a slightly larger pool size to handle potentially frequent, short-lived tasks.
     *
     * @return Configured ThreadPoolTaskExecutor for file monitoring/updates.
     */
    @Bean(name = TASK_EXECUTOR_FILE_MONITOR)
    public Executor taskExecutorFileMonitor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Adjust pool size based on expected file change frequency and processing time
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 2);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("FileMonitorThread-");
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
                 TASK_EXECUTOR_FILE_MONITOR, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * Defines a thread pool executor specifically for potentially CPU/GPU-bound embedding tasks.
     * Size might depend heavily on Ollama's ability to handle concurrent requests and available hardware.
     *
     * @return Configured ThreadPoolTaskExecutor for embedding generation.
     */
    @Bean(name = TASK_EXECUTOR_EMBEDDING)
    public Executor taskExecutorEmbedding() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        // Start conservatively, Ollama might be the bottleneck
        int corePoolSize = 2;
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(100); // Queue embedding requests
        executor.setThreadNamePrefix("EmbeddingThread-");
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
                 TASK_EXECUTOR_EMBEDDING, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * Defines a thread pool executor for vector store operations.
     * Optimized for handling requests to ChromaDB.
     *
     * @return Configured ThreadPoolTaskExecutor for vector store operations.
     */
    @Bean(name = TASK_EXECUTOR_VECTOR_STORE)
    public Executor taskExecutorVectorStore() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = 2;
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("VectorStoreThread-");
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
                 TASK_EXECUTOR_VECTOR_STORE, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    /**
     * Specifies the default executor to be used when @Async is used without a specific executor name.
     * Defaults to the file monitor executor for general async tasks.
     *
     * @return The default asynchronous task executor.
     */
    @Override
    public Executor getAsyncExecutor() {
        // Choose a sensible default, e.g., the file monitor executor
        return taskExecutorFileMonitor();
    }

    /**
     * Defines a handler for exceptions thrown from @Async methods that return void.
     *
     * @return An AsyncUncaughtExceptionHandler instance.
     */
    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncExceptionHandler();
    }

    /**
     * Custom exception handler for uncaught exceptions in async methods.
     */
    public static class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("Uncaught async error in method '{}'", method.getName(), ex);
            // Add more sophisticated error handling/reporting here if needed
        }
    }
} 
