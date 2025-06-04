package com.localllm.assistant.config;

import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.client5.http.impl.async.HttpAsyncClients;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManager;
import org.apache.hc.client5.http.impl.nio.PoolingAsyncClientConnectionManagerBuilder;
import org.apache.hc.core5.reactor.IOReactorConfig;
import org.apache.hc.core5.util.Timeout;
import org.apache.hc.core5.util.TimeValue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import jakarta.annotation.PreDestroy;
import lombok.Getter;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

@Configuration
@Getter
public class OllamaConfig {

    private static final Logger log = LoggerFactory.getLogger(OllamaConfig.class);

    // Base settings
    private final String baseUrl = "http://localhost:11434";
    private final String embeddingModel = "nomic-embed-text";
    private final String chatModel = "mistral-openorca:latest";

    // 🚨 FIXED TIMEOUT SETTINGS - Connection Pool Issue Solved
    private final int connectTimeoutMs = 60000;         // 1 dakika - yeterli
    private final int socketTimeoutMs = 600000;         // 10 DAKİKA - embedding için bol
    private final int connectionRequestTimeoutMs = 300000;  // 5 DAKİKA - pool'dan connection almak için

    // 🔧 ENLARGED CONNECTION POOL - More connections available
    private final int maxTotalConnections = 50;         // 10'dan 50'ye çıkardık
    private final int maxConnectionsPerRoute = 25;      // 5'ten 25'e çıkardık
    private final int connectionTimeToLiveMs = 1800000; // 30 dakika TTL (uzun)
    private final int connectionIdleTimeMs = 300000;    // 5 dakika idle (uzun)
    private final int validateAfterInactivityMs = 60000; // 1 dakika validation

    // ⚡ MORE IO THREADS
    private final int ioThreadCount = 8;                // 4'ten 8'e çıkardık
    private final boolean soKeepAlive = true;
    private final boolean tcpNoDelay = true;

    // Chat model settings
    private final int chatModelMaxPromptTokens = 3000;
    private final int chatModelDefaultNumPredict = 1024;

    private CloseableHttpAsyncClient sharedHttpAsyncClient;

    @Bean(name = "sharedHttpAsyncClient")
    public CloseableHttpAsyncClient sharedHttpAsyncClient() {
        log.info("Creating FIXED CloseableHttpAsyncClient - Connection Pool Issue Solved");

        // IO Reactor - More threads
        final IOReactorConfig ioReactorConfig = IOReactorConfig.custom()
            .setSoTimeout(Timeout.ofMilliseconds(socketTimeoutMs))
            .setIoThreadCount(ioThreadCount)
            .setSoKeepAlive(soKeepAlive)
            .setTcpNoDelay(tcpNoDelay)
            .setSoReuseAddress(true)
            .setSoLinger(TimeValue.ofSeconds(0))
            .build();

        // Connection Manager - BIGGER POOL
        final PoolingAsyncClientConnectionManager connectionManager = PoolingAsyncClientConnectionManagerBuilder.create()
            .setMaxConnTotal(maxTotalConnections)
            .setMaxConnPerRoute(maxConnectionsPerRoute)
            .setConnectionTimeToLive(TimeValue.ofMilliseconds(connectionTimeToLiveMs))
            .setValidateAfterInactivity(TimeValue.ofMilliseconds(validateAfterInactivityMs))
            .build();

        connectionManager.setDefaultMaxPerRoute(maxConnectionsPerRoute);

        // Request Config - LONGER TIMEOUTS
        final RequestConfig requestConfig = RequestConfig.custom()
            .setConnectTimeout(Timeout.ofMilliseconds(connectTimeoutMs))
            .setConnectionRequestTimeout(Timeout.ofMilliseconds(connectionRequestTimeoutMs)) // 5 DAKİKA!
            .setResponseTimeout(Timeout.ofMilliseconds(socketTimeoutMs))
            .setRedirectsEnabled(false)
            .setCircularRedirectsAllowed(false)
            .setMaxRedirects(0)
            .build();

        // HTTP Client
        sharedHttpAsyncClient = HttpAsyncClients.custom()
            .setIOReactorConfig(ioReactorConfig)
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictExpiredConnections()
            .evictIdleConnections(TimeValue.of(connectionIdleTimeMs, TimeUnit.MILLISECONDS))
            .disableAutomaticRetries()
            .disableCookieManagement()
            .disableRedirectHandling()
            .build();

        sharedHttpAsyncClient.start();

        log.info("FIXED CloseableHttpAsyncClient started: " +
                "connectTimeout={}s, socketTimeout={}s, connectionRequestTimeout={}s, " +
                "maxConnections={}, maxPerRoute={}, ioThreads={}",
            connectTimeoutMs/1000, socketTimeoutMs/1000, connectionRequestTimeoutMs/1000,
            maxTotalConnections, maxConnectionsPerRoute, ioThreadCount);

        return sharedHttpAsyncClient;
    }

    @PreDestroy
    public void closeHttpClient() {
        if (sharedHttpAsyncClient != null) {
            log.info("Closing shared CloseableHttpAsyncClient...");
            try {
                sharedHttpAsyncClient.close();
                log.info("Shared CloseableHttpAsyncClient closed gracefully.");
            } catch (IOException e) {
                log.error("Error closing shared CloseableHttpAsyncClient", e);
            }
        }
    }
}
