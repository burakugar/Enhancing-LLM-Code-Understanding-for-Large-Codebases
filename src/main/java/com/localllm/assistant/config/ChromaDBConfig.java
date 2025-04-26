package com.localllm.assistant.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

/**
 * Configuration properties for connecting to the local ChromaDB service via its HTTP API.
 */
@Configuration
@ConfigurationProperties(prefix = "chromadb") // Binds properties starting with 'chromadb'
@Getter
@Setter // Lombok annotations for boilerplate reduction (or generate manually)
public class ChromaDBConfig {

    /**
     * Base URL of the locally running ChromaDB HTTP API.
     * Example: http://localhost:8000
     */
    private String url = "http://localhost:8000";

    /**
     * Name of the default collection to use for storing code embeddings.
     */
    private String defaultCollectionName = "code_embeddings";

    /**
     * Timeout for connecting to the ChromaDB API in milliseconds.
     */
    private int connectTimeoutMs = 3000; // 3 seconds

    /**
     * Timeout for reading responses from the ChromaDB API in milliseconds.
     */
    private int readTimeoutMs = 30000; // 30 seconds

    /**
     * Name of the distance function to use for similarity search.
     * Options typically include 'l2' (Euclidean), 'ip' (Inner Product), 'cosine'.
     * Defaulting to cosine similarity, common for text/code embeddings.
     */
    private String distanceFunction = "cosine";

    // Note: We will use the same HttpClient bean defined in OllamaConfig
    // for ChromaDB interactions to share the connection pool, unless specific
    // configurations (like different timeouts) are needed for ChromaDB.
    // If separate client needed, define another HttpClient bean here.

    // toString() method for logging configuration (Lombok can generate this)
    @Override
    public String toString() {
        return "ChromaDBConfig{" +
               "url='" + url + '\'' +
               ", defaultCollectionName='" + defaultCollectionName + '\'' +
               ", connectTimeoutMs=" + connectTimeoutMs +
               ", readTimeoutMs=" + readTimeoutMs +
               ", distanceFunction='" + distanceFunction + '\'' +
               '}';
    }
} 