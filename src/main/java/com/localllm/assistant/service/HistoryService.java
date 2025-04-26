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
    
    /**
     * Creates a new conversation.
     * 
     * @param title Optional title for the conversation
     * @param repositoryPath Optional path to the code repository
     * @param initialMetadata Optional initial metadata for the conversation
     * @return The created conversation
     */
    Conversation createConversation(String title, String repositoryPath, Map<String, Object> initialMetadata);
    
    /**
     * Adds a new message to a conversation.
     * 
     * @param conversationId ID of the conversation
     * @param role Role of the message sender
     * @param content Content of the message
     * @param metadata Optional metadata for the message
     * @return The created message
     */
    ChatMessage addMessage(String conversationId, MessageRole role, String content, Map<String, Object> metadata);
    
    /**
     * Gets all messages in a conversation.
     * 
     * @param conversationId ID of the conversation
     * @return List of messages in chronological order
     */
    List<ChatMessage> getMessagesForConversation(String conversationId);
    
    /**
     * Gets recent messages in a conversation.
     * 
     * @param conversationId ID of the conversation
     * @param limit Maximum number of messages to retrieve
     * @return List of recent messages
     */
    List<ChatMessage> getRecentMessagesForConversation(String conversationId, int limit);
    
    /**
     * Gets a conversation by ID.
     * 
     * @param conversationId ID of the conversation to retrieve
     * @return Optional containing the conversation if found
     */
    Optional<Conversation> getConversation(String conversationId);
    
    /**
     * Gets all conversations.
     * 
     * @return List of all conversations
     */
    List<Conversation> getAllConversations();
    
    /**
     * Gets recent conversations.
     * 
     * @param limit Maximum number of conversations to retrieve
     * @return List of recent conversations
     */
    List<Conversation> getRecentConversations(int limit);
    
    /**
     * Updates the metadata of a conversation.
     * 
     * @param conversationId ID of the conversation
     * @param metadata New metadata (will be merged with existing)
     * @return Updated conversation
     */
    Conversation updateConversationMetadata(String conversationId, Map<String, Object> metadata);
    
    /**
     * Deletes a conversation and all its messages.
     * 
     * @param conversationId ID of the conversation to delete
     * @return true if conversation was found and deleted, false otherwise
     */
    boolean deleteConversation(String conversationId);
    
    /**
     * Searches for conversations by keyword in title.
     * 
     * @param keyword Keyword to search for
     * @return List of matching conversations
     */
    List<Conversation> searchConversationsByTitle(String keyword);
} 
