package com.localllm.assistant.service.impl;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.localllm.assistant.history.HistoryRepository;
import com.localllm.assistant.history.impl.H2HistoryRepositoryImpl;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.service.HistoryService;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Implementation of HistoryService that delegates to H2HistoryRepositoryImpl.
 * Provides conversation history management functions.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class HistoryServiceImpl implements HistoryService {

    private final H2HistoryRepositoryImpl historyRepositoryImpl;
    private final HistoryRepository historyRepository;

    @Override
    public Conversation createConversation(String title, String repositoryPath, Map<String, Object> initialMetadata) {
        return historyRepositoryImpl.createConversation(title, repositoryPath, initialMetadata);
    }

    @Override
    public ChatMessage addMessage(String conversationId, MessageRole role, String content, Map<String, Object> metadata) {
        return historyRepositoryImpl.addMessage(conversationId, role, content, metadata);
    }

    @Override
    public List<ChatMessage> getMessagesForConversation(String conversationId) {
        return historyRepositoryImpl.getConversationMessages(conversationId);
    }

    @Override
    public List<ChatMessage> getRecentMessagesForConversation(String conversationId, int limit) {
        return historyRepositoryImpl.getRecentMessages(conversationId, limit);
    }

    @Override
    public Optional<Conversation> getConversation(String conversationId) {
        return historyRepository.findById(conversationId);
    }

    @Override
    public List<Conversation> getAllConversations() {
        return historyRepository.findAll();
    }

    @Override
    public List<Conversation> getRecentConversations(int limit) {
        return historyRepositoryImpl.getRecentConversations(limit);
    }

    @Override
    public Conversation updateConversationMetadata(String conversationId, Map<String, Object> metadata) {
        return historyRepositoryImpl.updateConversationMetadata(conversationId, metadata);
    }

    @Override
    public boolean deleteConversation(String conversationId) {
        Optional<Conversation> conversation = historyRepository.findById(conversationId);
        if (conversation.isPresent()) {
            historyRepositoryImpl.deleteConversation(conversationId);
            return true;
        }
        return false;
    }

    @Override
    public List<Conversation> searchConversationsByTitle(String keyword) {
        return historyRepositoryImpl.searchConversationsByTitle(keyword);
    }
} 
