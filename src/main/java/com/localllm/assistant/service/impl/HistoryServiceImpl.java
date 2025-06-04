package com.localllm.assistant.service.impl;

import com.localllm.assistant.history.ChatMessageRepository;
import com.localllm.assistant.history.ConversationRepository;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class HistoryServiceImpl implements HistoryService {

    private static final Logger log = LoggerFactory.getLogger(HistoryServiceImpl.class);

    private final ConversationRepository conversationRepository;
    private final ChatMessageRepository chatMessageRepository;

    @Override
    @Transactional
    public Conversation createConversation(String id, String title, String repositoryPath, Map<String, Object> initialMetadata) {
        LocalDateTime now = LocalDateTime.now();
        String conversationIdToUse = (id == null || id.isBlank()) ? UUID.randomUUID().toString() : id;

        Optional<Conversation> existingConversation = conversationRepository.findById(conversationIdToUse);
        if (existingConversation.isPresent()) {
            log.debug("Conversation with ID {} already exists. Updating 'updatedAt'.", conversationIdToUse);
            Conversation conv = existingConversation.get();
            conv.setUpdatedAt(now);
            if (title != null && (conv.getTitle() == null || !conv.getTitle().equals(title))) {
                conv.setTitle(title);
            }
            if (initialMetadata != null && !initialMetadata.isEmpty()) {
                if (conv.getMetadata() == null) {
                    conv.setMetadata(new HashMap<>(initialMetadata));
                } else {
                    conv.getMetadata().putAll(initialMetadata);
                }
            }
            return conversationRepository.save(conv);
        }

        log.debug("Creating new conversation with ID: {}. Title: {}, Repo: {}", conversationIdToUse, title, repositoryPath);
        Conversation conversation = Conversation.builder()
            .id(conversationIdToUse)
            .title(title)
            .repositoryPath(repositoryPath)
            .createdAt(now)
            .updatedAt(now)
            .metadata(initialMetadata != null ? new HashMap<>(initialMetadata) : new HashMap<>())
            .build();
        Conversation saved = conversationRepository.save(conversation);
        log.info("Created new conversation with ID: {}", saved.getId());
        return saved;
    }

    @Override
    @Transactional
    public ChatMessage addMessage(String conversationId, MessageRole role, String content, Map<String, Object> metadata) {
        if (conversationId == null || conversationId.isBlank()) {
            log.error("ChatMessage must have a valid conversationId before saving.");
            throw new IllegalArgumentException("ChatMessage conversationId cannot be null or blank");
        }
        log.debug("Adding message. Role: {}, Conversation: {}", role, conversationId);

        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseGet(() -> {
                log.info("Conversation {} not found, creating a new one with this ID.", conversationId);
                String defaultTitle = (role == MessageRole.USER && content != null)
                    ? content.substring(0, Math.min(content.length(), 50)) + (content.length() > 50 ? "..." : "")
                    : "Conversation " + conversationId.substring(0, Math.min(conversationId.length(), 8)) + "...";
                return createConversation(conversationId, defaultTitle, null, null);
            });

        LocalDateTime now = LocalDateTime.now();
        conversation.setUpdatedAt(now);

        if (conversation.getTitle() == null && role == MessageRole.USER && content != null) {
            conversation.setTitle(content.substring(0, Math.min(content.length(), 50)) + (content.length() > 50 ? "..." : ""));
        }
        conversationRepository.save(conversation);

        ChatMessage message = ChatMessage.builder()
            .conversationId(conversation.getId())
            .timestamp(now)
            .role(role)
            .content(content)
            .metadata(metadata != null ? new HashMap<>(metadata) : new HashMap<>())
            .build();

        ChatMessage savedMessage = chatMessageRepository.save(message);
        log.debug("Saved chat message with ID {} for conversation {}", savedMessage.getId(), conversation.getId());
        return savedMessage;
    }

    @Override
    public List<ChatMessage> getMessagesForConversation(String conversationId) {
        log.debug("Retrieving all messages for conversation {}", conversationId);
        if (conversationId == null) {
            return Collections.emptyList();
        }
        return chatMessageRepository.findByConversationIdOrderByTimestampAsc(conversationId);
    }

    @Override
    public List<ChatMessage> getRecentMessagesForConversation(String conversationId, int limit) {
        log.debug("Retrieving last {} messages for conversation {}", limit, conversationId);
        if (conversationId == null || limit <= 0) {
            return Collections.emptyList();
        }
        Pageable pageable = PageRequest.of(0, limit, Sort.by("timestamp").descending());
        List<ChatMessage> recentMessages = chatMessageRepository.findByConversationIdOrderByTimestampDesc(conversationId, pageable);
        // Results are DESC, reverse to get them in ASC order for display (oldest of the recent first)
        Collections.reverse(recentMessages);
        return recentMessages;
    }

    @Override
    public Optional<Conversation> getConversation(String conversationId) {
        log.debug("Retrieving conversation with ID: {}", conversationId);
        if (conversationId == null) {
            return Optional.empty();
        }
        return conversationRepository.findById(conversationId);
    }

    @Override
    public List<Conversation> getAllConversations() {
        log.debug("Retrieving all conversations ordered by last updated.");
        return conversationRepository.findAllByOrderByUpdatedAtDesc();
    }

    @Override
    public List<Conversation> getRecentConversations(int limit) {
        log.debug("Retrieving last {} conversations.", limit);
        Pageable pageable = PageRequest.of(0, limit, Sort.by("updatedAt").descending());
        return conversationRepository.findAll(pageable).getContent();
    }

    @Override
    @Transactional
    public Conversation updateConversationMetadata(String conversationId, Map<String, Object> metadata) {
        log.debug("Updating metadata for conversation {}", conversationId);
        Conversation conversation = conversationRepository.findById(conversationId)
            .orElseThrow(() -> new IllegalArgumentException("Conversation not found: " + conversationId));

        Map<String, Object> existingMetadata = conversation.getMetadata();
        if (existingMetadata == null) {
            existingMetadata = new HashMap<>();
        }
        existingMetadata.putAll(metadata);
        conversation.setMetadata(existingMetadata);
        conversation.setUpdatedAt(LocalDateTime.now());
        return conversationRepository.save(conversation);
    }

    @Override
    @Transactional
    public boolean deleteConversation(String conversationId) {
        if (conversationId == null) {
            return false;
        }
        log.warn("Attempting to delete conversation and associated messages for ID: {}", conversationId);
        if (conversationRepository.existsById(conversationId)) {
            int deletedMessages = chatMessageRepository.deleteByConversationId(conversationId);
            log.info("Deleted {} messages for conversation {}", deletedMessages, conversationId);
            conversationRepository.deleteById(conversationId);
            log.info("Deleted conversation {}", conversationId);
            return true;
        } else {
            log.warn("Conversation with ID {} not found for deletion.", conversationId);
            return false;
        }
    }

    @Override
    public List<Conversation> searchConversationsByTitle(String keyword) {
        log.warn("searchConversationsByTitle not fully implemented yet. Requires custom repository method.");
        return conversationRepository.findAll().stream()
            .filter(c -> c.getTitle() != null && c.getTitle().toLowerCase().contains(keyword.toLowerCase()))
            .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public void deleteAllConversations() {
        log.warn("Deleting ALL conversations and their messages.");
        List<Conversation> allConversations = conversationRepository.findAll();
        for (Conversation conversation : allConversations) {
            chatMessageRepository.deleteByConversationId(conversation.getId());
        }
        conversationRepository.deleteAll();
        log.info("Successfully deleted all conversations and messages.");
    }
}
