package com.localllm.assistant.history;

import com.localllm.assistant.history.model.Conversation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data JPA repository for managing Conversation entities.
 */
@Repository
public interface ConversationRepository extends JpaRepository<Conversation, String> {

    /**
     * Finds all conversations ordered by the last updated timestamp in descending order.
     * Spring Data JPA automatically generates the query from the method name.
     *
     * @return A list of conversations ordered by updatedAt descending.
     */
    List<Conversation> findAllByOrderByUpdatedAtDesc();

}
