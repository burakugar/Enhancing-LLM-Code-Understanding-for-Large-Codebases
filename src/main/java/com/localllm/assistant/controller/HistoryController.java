package com.localllm.assistant.controller;

import com.localllm.assistant.controller.dto.HistoryResponse;
import com.localllm.assistant.controller.dto.QueryResponse;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.service.HistoryService;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.format.DateTimeFormatter;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/history")
@RequiredArgsConstructor
public class HistoryController {

    private static final Logger log = LoggerFactory.getLogger(HistoryController.class);
    private final HistoryService historyService;

    @GetMapping("/{conversationId}")
    public ResponseEntity<HistoryResponse> getConversationHistory(@PathVariable String conversationId) {
        log.debug("Request received for history of conversation: {}", conversationId);

        Conversation conversation = historyService.getConversation(conversationId).orElse(null);
        if (conversation == null) {
            log.warn("Conversation not found: {}", conversationId);
            return ResponseEntity.notFound().build();
        }

        List<ChatMessage> messages = historyService.getMessagesForConversation(conversationId);
        List<HistoryResponse.HistoryMessage> messageDtos = messages.stream()
            .map(this::mapToHistoryMessageDto)
            .collect(Collectors.toList());

        HistoryResponse response = HistoryResponse.builder()
            .conversationId(conversationId)
            .title(conversation.getTitle())
            .createdAt(conversation.getCreatedAt() != null ? conversation.getCreatedAt().format(DateTimeFormatter.ISO_DATE_TIME) : null)
            .updatedAt(conversation.getUpdatedAt() != null ? conversation.getUpdatedAt().format(DateTimeFormatter.ISO_DATE_TIME) : null)
            .messages(messageDtos)
            .build();
        return ResponseEntity.ok(response);
    }

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
                    .title(conversation.getTitle())
                    .createdAt(conversation.getCreatedAt() != null ? conversation.getCreatedAt().format(DateTimeFormatter.ISO_DATE_TIME) : null)
                    .updatedAt(conversation.getUpdatedAt() != null ? conversation.getUpdatedAt().format(DateTimeFormatter.ISO_DATE_TIME) : null)
                    .messages(messageDtos)
                    .build();
            })
            .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    /**
     * Deletes all conversations and their messages.
     *
     * @return 204 No Content if successful.
     */
    @DeleteMapping("/all")
    public ResponseEntity<Void> deleteAllConversations() {
        log.warn("Request received to delete ALL conversations.");
        try {
            historyService.deleteAllConversations();
            return ResponseEntity.noContent().build();
        } catch (Exception e) {
            log.error("Error deleting all conversations: {}", e.getMessage(), e);
            return ResponseEntity.internalServerError().build();
        }
    }

    private HistoryResponse.HistoryMessage mapToHistoryMessageDto(ChatMessage msg) {
        @SuppressWarnings("unchecked") // Suppress warning for cast if metadata structure is known
        List<QueryResponse.SourceReference> sources = (msg.getMetadata() != null && msg.getMetadata().containsKey("codeReferences")) ?
            (List<QueryResponse.SourceReference>) msg.getMetadata().get("codeReferences") : Collections.emptyList();

        return HistoryResponse.HistoryMessage.builder()
            .role(msg.getRole().name().toLowerCase())
            .content(msg.getContent())
            .timestamp(msg.getTimestamp() != null ? msg.getTimestamp().format(DateTimeFormatter.ISO_DATE_TIME) : null)
            .sources(sources)
            .build();
    }
}
