package com.localllm.assistant.history.model;

import java.time.LocalDateTime;
import java.util.Map;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
    // Assuming conversation ID is generated externally (e.g., by the service layer)
    // or use @GeneratedValue(strategy = GenerationType.UUID) if DB should generate
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

    // Relationship to messages (optional, can be queried separately)
    // @OneToMany(mappedBy = "conversation", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    // private List<ChatMessage> messages;
} 