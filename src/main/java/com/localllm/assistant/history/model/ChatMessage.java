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
 * Represents a single message within a conversation history, stored in the H2 database.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Entity // Mark as JPA entity
@Table(name = "chat_messages") // Specify table name
public class ChatMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID) // Use UUID for primary key
    private String id;

    @Column(nullable = false, name = "conversation_id")
    private String conversationId; // Foreign key to Conversation entity

    @Column(nullable = false)
    private LocalDateTime timestamp;

    @Enumerated(EnumType.STRING) // Store enum as string
    @Column(nullable = false)
    private MessageRole role;

    @Lob // Large object for potentially long content
    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    /**
     * Stores metadata related to the message, such as code references used by the assistant.
     * Stored as JSONB in H2.
     * Example: {"codeReferences": [{"id": "seg1", "filePath": "..."}, ...]}
     */
    @JdbcTypeCode(SqlTypes.JSON) // Hibernate specific type for JSON
    @Column(columnDefinition = "JSON") // H2 supports JSON type
    private Map<String, Object> metadata;

    // Optional: Link back to the parent Conversation entity
    // @ManyToOne(fetch = FetchType.LAZY)
    // @JoinColumn(name = "conversation_id", insertable = false, updatable = false)
    // private Conversation conversation;
} 