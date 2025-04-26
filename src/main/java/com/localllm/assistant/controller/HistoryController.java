package com.localllm.assistant.controller;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import com.localllm.assistant.controller.dto.HistoryResponse;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.service.HistoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import lombok.RequiredArgsConstructor;

/**
 * REST controller for conversation history operations.
 * Provides endpoints to retrieve and manage conversation history.
 */
@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    private final HistoryService historyService;

    /**
     * Retrieves the full history of a conversation.
     *
     * @param conversationId The ID of the conversation to retrieve
     * @return The conversation history with all messages
     */
    @GetMapping("/{conversationId}")
    public ResponseEntity<HistoryResponse> getConversationHistory(@PathVariable String conversationId) {
        log.debug("Request received for history of conversation: {}", conversationId);
        List<ChatMessage> messages = historyService.getMessagesForConversation(conversationId);
        
        if (messages.isEmpty()) {
            // Check if conversation itself exists to differentiate empty vs not found
            if (!historyService.getConversation(conversationId).isPresent()) {
                log.warn("Conversation not found: {}", conversationId);
                return ResponseEntity.notFound().build();
            }
        }

        List<HistoryResponse.HistoryMessage> messageDtos = messages.stream()
            .map(this::mapToHistoryMessageDto)
            .collect(Collectors.toList());

        HistoryResponse response = HistoryResponse.builder()
            .conversationId(conversationId)
            .messages(messageDtos)
            .build();
        return ResponseEntity.ok(response);
    }

    /**
     * Deletes a conversation and all its messages.
     *
     * @param conversationId The ID of the conversation to delete
     * @return 204 No Content if successful, 404 Not Found if conversation doesn't exist
     */
    @DeleteMapping("/{conversationId}")
    public ResponseEntity<Void> deleteConversationHistory(@PathVariable String conversationId) {
        log.warn("Request received to delete history for conversation: {}", conversationId);
        try {
            boolean deleted = historyService.deleteConversation(conversationId);
            if (deleted) {
                return ResponseEntity.noContent().build();
            } else {
                return ResponseEntity.notFound().build();
            }
        } catch (Exception e) {
            log.error("Error deleting conversation {}: {}", conversationId, e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * Lists all conversations.
     *
     * @return A list of all conversations sorted by last updated time
     */
    @GetMapping
    public ResponseEntity<List<HistoryResponse>> getAllConversations() {
        log.debug("Request received to list all conversations");
        List<HistoryResponse> responses = historyService.getAllConversations().stream()
            .map(conversation -> {
                List<ChatMessage> recentMessages = historyService.getRecentMessagesForConversation(
                    conversation.getId(), 3); // Get just a few recent messages
                
                List<HistoryResponse.HistoryMessage> messageDtos = recentMessages.stream()
                    .map(this::mapToHistoryMessageDto)
                    .collect(Collectors.toList());
                
                return HistoryResponse.builder()
                    .conversationId(conversation.getId())
                    .messages(messageDtos)
                    .build();
            })
            .collect(Collectors.toList());
        
        return ResponseEntity.ok(responses);
    }

    /**
     * Maps a ChatMessage entity to a HistoryMessage DTO.
     *
     * @param msg The ChatMessage to map
     * @return The mapped HistoryMessage DTO
     */
    private HistoryResponse.HistoryMessage mapToHistoryMessageDto(ChatMessage msg) {
        // Basic mapping, enhance as needed (e.g., parsing metadata for sources)
        return HistoryResponse.HistoryMessage.builder()
                .role(msg.getRole().name().toLowerCase()) // e.g., "user", "assistant"
                .content(msg.getContent())
                .timestamp(msg.getTimestamp() != null ? msg.getTimestamp().format(DateTimeFormatter.ISO_DATE_TIME) : null)
                // TODO: Parse msg.getMetadata() to extract and map code sources if present
                .sources(Collections.emptyList()) // Placeholder
                .build();
    }
} 
