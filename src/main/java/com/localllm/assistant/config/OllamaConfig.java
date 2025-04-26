package com.localllm.assistant.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.core5.util.TimeValue;
import org.apache.hc.core5.util.Timeout;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties for connecting to the local Ollama service.
 */
@Configuration
@ConfigurationProperties(prefix = "ollama") // Binds properties starting with 'ollama'
@Getter
@Setter // Lombok annotations for boilerplate reduction (or generate manually)
public class OllamaConfig {

    /**
     * Base URL of the locally running Ollama service.
     * Example: http://localhost:11434
     */
    private String baseUrl = "http://localhost:11434";

    /**
     * Name of the Ollama model to use for generating embeddings.
     * Example: codellama:7b-instruct
     */
    private String embeddingModel = "codellama:7b-instruct";

    /**
     * Name of the Ollama model to use for generating chat/query responses.
     * Example: llama3:8b-instruct
     */
    private String chatModel = "llama3:8b-instruct";

    /**
     * Connection timeout for Ollama API requests in milliseconds.
     */
    private int connectTimeoutMs = 5000; // 5 seconds

    /**
     * Socket read timeout for Ollama API requests in milliseconds.
     * Should be long enough for model generation.
     */
    private int socketTimeoutMs = 120000; // 120 seconds (2 minutes)

    /**
     * Maximum number of connections in the HTTP client pool.
     */
    private int maxTotalConnections = 50;

    /**
     * Maximum number of connections per route (to the Ollama base URL).
     */
    private int maxConnectionsPerRoute = 20;

    /**
     * Creates a configured Apache HttpClient 5 instance for communicating with Ollama.
     * Uses a connection pool for efficiency.
     *
     * @return A CloseableHttpClient instance.
     */
    @Bean
    public CloseableHttpClient ollamaHttpClient() {
        PoolingHttpClientConnectionManager connectionManager = new PoolingHttpClientConnectionManager();
        connectionManager.setMaxTotal(maxTotalConnections);
        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);
        // Optional: Configure socket settings if needed
        // connectionManager.setDefaultSocketConfig(SocketConfig.custom().setSoTimeout(TimeValue.ofMilliseconds(socketTimeoutMs)).build());

        RequestConfig requestConfig = RequestConfig.custom()
                .setConnectTimeout((Timeout) TimeValue.ofMilliseconds(connectTimeoutMs))
                .setConnectionRequestTimeout((Timeout) TimeValue.ofMilliseconds(connectTimeoutMs)) // Timeout waiting for conn from pool
                // Socket timeout is often set per-request, but can be defaulted here
                // .setResponseTimeout(TimeValue.ofMilliseconds(socketTimeoutMs)) // Use setResponseTimeout in HttpClient 5.x
                .build();

        return HttpClients.custom()
                .setConnectionManager(connectionManager)
                .setDefaultRequestConfig(requestConfig)
                // Optional: Add interceptors, retry handlers, etc.
                .build();
    }

    // toString() method for logging configuration (Lombok can generate this)
    @Override
    public String toString() {
        return "OllamaConfig{" +
               "baseUrl='" + baseUrl + '\'' +
               ", embeddingModel='" + embeddingModel + '\'' +
               ", chatModel='" + chatModel + '\'' +
               ", connectTimeoutMs=" + connectTimeoutMs +
               ", socketTimeoutMs=" + socketTimeoutMs +
               '}';
    }
} 
