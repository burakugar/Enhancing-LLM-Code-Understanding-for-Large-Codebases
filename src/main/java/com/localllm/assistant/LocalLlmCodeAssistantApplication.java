package com.localllm.assistant;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * Main application class for the Local LLM Code Assistant.
 * Initializes the Spring Boot application context.
 */
@SpringBootApplication
@EnableAsync
public class LocalLlmCodeAssistantApplication {

    /**
     * The main method that serves as the entry point for the Spring Boot application.
     *
     * @param args Command line arguments passed to the application.
     */
    public static void main(String[] args) {
        SpringApplication.run(LocalLlmCodeAssistantApplication.class, args);
    }
} 
