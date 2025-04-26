package com.localllm.assistant.history;

import java.util.List;
import java.util.Optional;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;

/**
 * Spring Data JPA repository interface for managing Conversation and ChatMessage entities
 * in the H2 database.
 */
@Repository
public interface HistoryRepository extends JpaRepository<Conversation, String> { // Manages Conversation entities, ID is String
    
    /**
     * Find conversations sorted by updated time (newest first).
     * 
     * @return List of conversations ordered by last update time
     */
    List<Conversation> findByOrderByUpdatedAtDesc();
    
    /**
     * Find conversations by repository path.
     * 
     * @param repositoryPath Path to repository
     * @return List of conversations for the given repository path
     */
    List<Conversation> findByRepositoryPath(String repositoryPath);
    
    /**
     * Find a conversation by title.
     * 
     * @param title Title to search for
     * @return Optional containing the conversation if found
     */
    Optional<Conversation> findByTitle(String title);
    
    /**
     * Custom query to find messages for a specific conversation.
     * 
     * @param conversationId ID of the conversation
     * @return List of messages in chronological order
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = ?1 ORDER BY m.timestamp ASC")
    List<ChatMessage> findMessagesByConversationId(String conversationId);
    
    /**
     * Custom query to find recent messages for a specific conversation.
     * 
     * @param conversationId ID of the conversation
     * @param pageable Pagination information
     * @return List of recent messages in reverse chronological order
     */
    @Query("SELECT m FROM ChatMessage m WHERE m.conversationId = ?1 ORDER BY m.timestamp DESC")
    List<ChatMessage> findRecentMessagesByConversationId(String conversationId, Pageable pageable);
    
    /**
     * Delete all messages for a conversation.
     * 
     * @param conversationId ID of the conversation
     */
    @Query("DELETE FROM ChatMessage m WHERE m.conversationId = ?1")
    void deleteMessagesByConversationId(String conversationId);
} 