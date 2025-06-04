package com.localllm.assistant.history;

import com.localllm.assistant.history.model.ChatMessage;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Spring Data JPA repository for managing ChatMessage entities.
 */
@Repository
public interface ChatMessageRepository extends JpaRepository<ChatMessage, String> { // Manages ChatMessage, ID is String

    /**
     * Finds all messages belonging to a specific conversation, ordered by timestamp ascending.
     *
     * @param conversationId The ID of the conversation.
     * @return A list of chat messages ordered by timestamp ascending.
     */
    List<ChatMessage> findByConversationIdOrderByTimestampAsc(String conversationId);

    /**
     * Finds the most recent messages belonging to a specific conversation, ordered by timestamp descending,
     * using pagination to limit the results.
     *
     * @param conversationId The ID of the conversation.
     * @param pageable       Pagination information (e.g., PageRequest.of(0, limit, Sort.by("timestamp").descending())).
     * @return A list of the most recent chat messages for the conversation.
     */
    List<ChatMessage> findByConversationIdOrderByTimestampDesc(String conversationId, Pageable pageable);

    /**
     * Deletes all chat messages associated with a specific conversation ID.
     * Requires @Modifying and @Transactional annotations for delete operations.
     *
     * @param conversationId The ID of the conversation whose messages should be deleted.
     * @return The number of messages deleted.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM ChatMessage cm WHERE cm.conversationId = :conversationId")
    int deleteByConversationId(@Param("conversationId") String conversationId);

}
