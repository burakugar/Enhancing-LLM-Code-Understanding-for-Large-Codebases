package com.localllm.assistant.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

/**
 * General application configuration beans.
 */
@Configuration
public class AppConfig {

    /**
     * Provides a customized ObjectMapper bean for JSON serialization/deserialization.
     * Configures handling for Java 8 date/time types and disables writing dates as timestamps.
     *
     * @return A configured ObjectMapper instance.
     */
    @Bean
    @Primary // Ensure this ObjectMapper is used by default
    public ObjectMapper objectMapper() {
        ObjectMapper objectMapper = new ObjectMapper();
        // Register module for Java 8 Date/Time API (LocalDate, LocalDateTime, etc.)
        objectMapper.registerModule(new JavaTimeModule());
        // Configure not to write dates as timestamps (e.g., write as ISO-8601 strings)
        objectMapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        // Configure to ignore unknown properties during deserialization
        // objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        // Configure pretty printing (useful for debugging, disable in production if needed)
        // objectMapper.enable(SerializationFeature.INDENT_OUTPUT);
        return objectMapper;
    }

    // Add other general-purpose beans here if needed later
    // Example: RestTemplate, specific utility beans, etc.
} 