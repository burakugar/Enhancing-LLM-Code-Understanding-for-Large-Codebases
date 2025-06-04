package com.localllm.assistant.service;

import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.history.model.MessageRole;

import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Service interface for managing conversation history and messages.
 */
public interface HistoryService {

    Conversation createConversation(String id, String title, String repositoryPath, Map<String, Object> initialMetadata);
    ChatMessage addMessage(String conversationId, MessageRole role, String content, Map<String, Object> metadata);
    List<ChatMessage> getMessagesForConversation(String conversationId);
    List<ChatMessage> getRecentMessagesForConversation(String conversationId, int limit);
    Optional<Conversation> getConversation(String conversationId);
    List<Conversation> getAllConversations();
    List<Conversation> getRecentConversations(int limit);
    Conversation updateConversationMetadata(String conversationId, Map<String, Object> metadata);
    boolean deleteConversation(String conversationId);
    List<Conversation> searchConversationsByTitle(String keyword);
    /**
     * Deletes all conversations and their associated messages.
     */
    void deleteAllConversations(); // New method
}
