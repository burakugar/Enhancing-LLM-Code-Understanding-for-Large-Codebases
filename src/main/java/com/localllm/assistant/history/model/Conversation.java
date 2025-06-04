package com.localllm.assistant.history.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a single conversation thread containing multiple chat messages.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity
@Table(name = "conversations")
public class Conversation {

    @Id
    private String id;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Optional title for the conversation, potentially derived from the first user query.
     */
    @Column
    private String title;

    /**
     * Optional: Path to the codebase this conversation relates to, if applicable.
     */
    @Column
    private String repositoryPath;

    /**
     * Additional metadata about the conversation.
     * Example: {"userId": "user123", "tags": ["refactoring", "auth"]}
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private Map<String, Object> metadata;

}
