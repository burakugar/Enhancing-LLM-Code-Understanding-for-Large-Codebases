package com.localllm.assistant.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.aop.interceptor.AsyncUncaughtExceptionHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.lang.reflect.Method;
import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

@Configuration
public class AsyncConfig implements AsyncConfigurer {

    private static final Logger log = LoggerFactory.getLogger(AsyncConfig.class);

    public static final String TASK_EXECUTOR_ORCHESTRATION = "taskExecutorOrchestration";
    public static final String TASK_EXECUTOR_PARSING = "taskExecutorParsing";
    public static final String TASK_EXECUTOR_FILE_MONITOR = "taskExecutorFileMonitor";
    public static final String TASK_EXECUTOR_EMBEDDING = "taskExecutorEmbedding";
    public static final String TASK_EXECUTOR_VECTOR_STORE = "taskExecutorVectorStore";

    @Bean(name = TASK_EXECUTOR_ORCHESTRATION)
    public Executor taskExecutorOrchestration() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(1);
        executor.setMaxPoolSize(2);
        executor.setQueueCapacity(50);
        executor.setThreadNamePrefix("OrchestrationThread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
            TASK_EXECUTOR_ORCHESTRATION, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Bean(name = TASK_EXECUTOR_PARSING)
    public Executor taskExecutorParsing() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();

        executor.setCorePoolSize(Runtime.getRuntime().availableProcessors() * 2);
        executor.setMaxPoolSize(Runtime.getRuntime().availableProcessors() * 4);
        executor.setQueueCapacity(500);
        executor.setThreadNamePrefix("ParsingThread-");

        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());

        executor.setAllowCoreThreadTimeOut(true);
        executor.setKeepAliveSeconds(60);

        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
            TASK_EXECUTOR_PARSING, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Bean(name = TASK_EXECUTOR_FILE_MONITOR)
    public Executor taskExecutorFileMonitor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = Math.max(2, Runtime.getRuntime().availableProcessors() / 4);
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("FileMonitorThread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
            TASK_EXECUTOR_FILE_MONITOR, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Bean(name = TASK_EXECUTOR_EMBEDDING)
    public Executor taskExecutorEmbedding() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = 2;
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("EmbeddingThread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
            TASK_EXECUTOR_EMBEDDING, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Bean(name = TASK_EXECUTOR_VECTOR_STORE)
    public Executor taskExecutorVectorStore() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        int corePoolSize = 2;
        executor.setCorePoolSize(corePoolSize);
        executor.setMaxPoolSize(corePoolSize * 2);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("VectorStoreThread-");
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        log.info("Initialized '{}' with CorePoolSize={}, MaxPoolSize={}, QueueCapacity={}",
            TASK_EXECUTOR_VECTOR_STORE, executor.getCorePoolSize(), executor.getMaxPoolSize(), executor.getQueueCapacity());
        return executor;
    }

    @Override
    public Executor getAsyncExecutor() {
        return taskExecutorFileMonitor();
    }

    @Override
    public AsyncUncaughtExceptionHandler getAsyncUncaughtExceptionHandler() {
        return new AsyncExceptionHandler();
    }

    public static class AsyncExceptionHandler implements AsyncUncaughtExceptionHandler {
        private static final Logger log = LoggerFactory.getLogger(AsyncExceptionHandler.class);

        @Override
        public void handleUncaughtException(Throwable ex, Method method, Object... params) {
            log.error("Uncaught async error in method '{}' with params '{}'", method.getName(), params, ex);
        }
    }
}
