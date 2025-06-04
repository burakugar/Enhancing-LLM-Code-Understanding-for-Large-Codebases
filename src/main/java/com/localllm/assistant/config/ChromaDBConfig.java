package com.localllm.assistant.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import lombok.Getter;
import lombok.Setter;

@Configuration
@ConfigurationProperties(prefix = "chromadb")
@Getter
@Setter
public class ChromaDBConfig {

    private String url = "http://localhost:8000";
    private String defaultCollectionName = "code_embeddings";
    private int connectTimeoutMs = 3000;
    private int readTimeoutMs = 30000;
    private String distanceFunction = "cosine";

    @Value("${chromadb.embedding-dimension}")
    private int embeddingDimension;

    private int batchSize = 200;

    @Override
    public String toString() {
        return "ChromaDBConfig{" +
            "url='" + url + '\'' +
            ", defaultCollectionName='" + defaultCollectionName + '\'' +
            ", connectTimeoutMs=" + connectTimeoutMs +
            ", readTimeoutMs=" + readTimeoutMs +
            ", distanceFunction='" + distanceFunction + '\'' +
            ", embeddingDimension=" + embeddingDimension +
            ", batchSize=" + batchSize +
            '}';
    }
}
