package com.localllm.assistant;

import com.localllm.assistant.history.ChatMessageRepository;
import com.localllm.assistant.history.ConversationRepository;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.service.impl.HistoryServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HistoryServiceImplTest {

    @Mock
    private ConversationRepository conversationRepository;

    @Mock
    private ChatMessageRepository chatMessageRepository;

    @InjectMocks
    private HistoryServiceImpl historyService;

    private Conversation testConversation;
    private ChatMessage testMessage;
    private LocalDateTime testTime;

    @BeforeEach
    void setUp() {
        testTime = LocalDateTime.now();

        testConversation = Conversation.builder()
            .id("test-conversation-id")
            .title("Test Conversation")
            .repositoryPath("/test/repo")
            .createdAt(testTime)
            .updatedAt(testTime)
            .metadata(new HashMap<>())
            .build();

        testMessage = ChatMessage.builder()
            .id(String.valueOf(1L))
            .conversationId("test-conversation-id")
            .timestamp(testTime)
            .role(MessageRole.USER)
            .content("Test message content")
            .metadata(new HashMap<>())
            .build();
    }

    @Test
    void testCreateConversation_NewConversation() {
        // Given
        String id = "new-conversation-id";
        String title = "New Conversation";
        String repositoryPath = "/new/repo";
        Map<String, Object> metadata = Map.of("key", "value");

        when(conversationRepository.findById(id)).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Conversation result = historyService.createConversation(id, title, repositoryPath, metadata);

        // Then
        assertNotNull(result);
        assertEquals(id, result.getId());
        assertEquals(title, result.getTitle());
        assertEquals(repositoryPath, result.getRepositoryPath());
        assertEquals(metadata.get("key"), result.getMetadata().get("key"));
        assertNotNull(result.getCreatedAt());
        assertNotNull(result.getUpdatedAt());

        verify(conversationRepository).findById(id);
        verify(conversationRepository).save(any(Conversation.class));
    }

    @Test
    void testCreateConversation_ExistingConversation() {
        // Given
        String id = "existing-conversation-id";
        String newTitle = "Updated Title";
        Map<String, Object> newMetadata = Map.of("newKey", "newValue");

        Conversation existingConversation = Conversation.builder()
            .id(id)
            .title("Old Title")
            .createdAt(testTime.minusDays(1))
            .updatedAt(testTime.minusDays(1))
            .metadata(new HashMap<>(Map.of("oldKey", "oldValue")))
            .build();

        when(conversationRepository.findById(id)).thenReturn(Optional.of(existingConversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Conversation result = historyService.createConversation(id, newTitle, null, newMetadata);

        // Then
        assertEquals(newTitle, result.getTitle());
        assertTrue(result.getUpdatedAt().isAfter(testTime.minusDays(1)));
        assertEquals("oldValue", result.getMetadata().get("oldKey"));
        assertEquals("newValue", result.getMetadata().get("newKey"));

        verify(conversationRepository).save(existingConversation);
    }

    @Test
    void testCreateConversation_WithNullId() {
        // Given
        when(conversationRepository.findById(anyString())).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Conversation result = historyService.createConversation(null, "Title", "/repo", null);

        // Then
        assertNotNull(result.getId());
        assertTrue(result.getId().length() > 0);
        assertEquals("Title", result.getTitle());
    }

    @Test
    void testAddMessage_ExistingConversation() {
        // Given
        String conversationId = "test-conversation-id";
        MessageRole role = MessageRole.USER;
        String content = "Test message";
        Map<String, Object> metadata = Map.of("key", "value");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(testConversation));
        when(conversationRepository.save(any(Conversation.class))).thenReturn(testConversation);
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(String.valueOf(1L));
            return message;
        });

        // When
        ChatMessage result = historyService.addMessage(conversationId, role, content, metadata);

        // Then
        assertNotNull(result);
        assertEquals(conversationId, result.getConversationId());
        assertEquals(role, result.getRole());
        assertEquals(content, result.getContent());
        assertEquals("value", result.getMetadata().get("key"));
        assertNotNull(result.getTimestamp());

        verify(conversationRepository).findById(conversationId);
        verify(conversationRepository).save(testConversation);
        verify(chatMessageRepository).save(any(ChatMessage.class));
    }

    @Test
    void testAddMessage_NonExistentConversation() {
        // Given
        String conversationId = "non-existent-id";
        MessageRole role = MessageRole.USER;
        String content = "Test message for new conversation";

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(String.valueOf(1L));
            return message;
        });

        // When
        ChatMessage result = historyService.addMessage(conversationId, role, content, null);

        // Then
        assertNotNull(result);
        assertEquals(conversationId, result.getConversationId());
        assertEquals(role, result.getRole());
        assertEquals(content, result.getContent());

        // Verify that a new conversation was created
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository, times(2)).save(conversationCaptor.capture());

        List<Conversation> savedConversations = conversationCaptor.getAllValues();
        Conversation createdConversation = savedConversations.get(0);
        assertEquals(conversationId, createdConversation.getId());
        assertTrue(createdConversation.getTitle().startsWith("Test message for new conversation"));
    }

    @Test
    void testAddMessage_NullConversationId() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            historyService.addMessage(null, MessageRole.USER, "content", null));

        assertEquals("ChatMessage conversationId cannot be null or blank", exception.getMessage());
    }

    @Test
    void testAddMessage_BlankConversationId() {
        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            historyService.addMessage("", MessageRole.USER, "content", null));

        assertEquals("ChatMessage conversationId cannot be null or blank", exception.getMessage());
    }

    @Test
    void testGetMessagesForConversation() {
        // Given
        String conversationId = "test-conversation-id";
        List<ChatMessage> messages = Arrays.asList(testMessage);

        when(chatMessageRepository.findByConversationIdOrderByTimestampAsc(conversationId))
            .thenReturn(messages);

        // When
        List<ChatMessage> result = historyService.getMessagesForConversation(conversationId);

        // Then
        assertEquals(1, result.size());
        assertEquals(testMessage, result.get(0));
        verify(chatMessageRepository).findByConversationIdOrderByTimestampAsc(conversationId);
    }

    @Test
    void testGetMessagesForConversation_NullId() {
        // When
        List<ChatMessage> result = historyService.getMessagesForConversation(null);

        // Then
        assertTrue(result.isEmpty());
        verify(chatMessageRepository, never()).findByConversationIdOrderByTimestampAsc(anyString());
    }

    @Test
    void testGetRecentMessagesForConversation() {
        // Given
        String conversationId = "test-conversation-id";
        int limit = 5;

        ChatMessage message1 = ChatMessage.builder()
            .id(String.valueOf(1L))
            .conversationId(conversationId)
            .timestamp(testTime.minusMinutes(2))
            .role(MessageRole.USER)
            .content("First message")
            .build();

        ChatMessage message2 = ChatMessage.builder()
            .id(String.valueOf(2L))
            .conversationId(conversationId)
            .timestamp(testTime.minusMinutes(1))
            .role(MessageRole.ASSISTANT)
            .content("Second message")
            .build();

        // Messages returned in DESC order (most recent first)
        List<ChatMessage> messagesDesc = Arrays.asList(message2, message1);

        when(chatMessageRepository.findByConversationIdOrderByTimestampDesc(eq(conversationId), any(Pageable.class)))
            .thenReturn(messagesDesc);

        // When
        List<ChatMessage> result = historyService.getRecentMessagesForConversation(conversationId, limit);

        // Then
        assertEquals(2, result.size());
        // Should be reversed to ASC order (oldest first)
        assertEquals(message1, result.get(0));
        assertEquals(message2, result.get(1));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(chatMessageRepository).findByConversationIdOrderByTimestampDesc(eq(conversationId), pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(limit, pageable.getPageSize());
        assertEquals(Sort.by("timestamp").descending(), pageable.getSort());
    }

    @Test
    void testGetRecentMessagesForConversation_InvalidParams() {
        // Test null conversation ID
        List<ChatMessage> result1 = historyService.getRecentMessagesForConversation(null, 5);
        assertTrue(result1.isEmpty());

        // Test zero limit
        List<ChatMessage> result2 = historyService.getRecentMessagesForConversation("id", 0);
        assertTrue(result2.isEmpty());

        // Test negative limit
        List<ChatMessage> result3 = historyService.getRecentMessagesForConversation("id", -1);
        assertTrue(result3.isEmpty());

        verify(chatMessageRepository, never()).findByConversationIdOrderByTimestampDesc(anyString(), any(Pageable.class));
    }

    @Test
    void testGetConversation() {
        // Given
        String conversationId = "test-conversation-id";
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(testConversation));

        // When
        Optional<Conversation> result = historyService.getConversation(conversationId);

        // Then
        assertTrue(result.isPresent());
        assertEquals(testConversation, result.get());
        verify(conversationRepository).findById(conversationId);
    }

    @Test
    void testGetConversation_NotFound() {
        // Given
        String conversationId = "non-existent-id";
        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        // When
        Optional<Conversation> result = historyService.getConversation(conversationId);

        // Then
        assertFalse(result.isPresent());
        verify(conversationRepository).findById(conversationId);
    }

    @Test
    void testGetConversation_NullId() {
        // When
        Optional<Conversation> result = historyService.getConversation(null);

        // Then
        assertFalse(result.isPresent());
        verify(conversationRepository, never()).findById(anyString());
    }

    @Test
    void testGetAllConversations() {
        // Given
        List<Conversation> conversations = Arrays.asList(testConversation);
        when(conversationRepository.findAllByOrderByUpdatedAtDesc()).thenReturn(conversations);

        // When
        List<Conversation> result = historyService.getAllConversations();

        // Then
        assertEquals(1, result.size());
        assertEquals(testConversation, result.get(0));
        verify(conversationRepository).findAllByOrderByUpdatedAtDesc();
    }

    @Test
    void testGetRecentConversations() {
        // Given
        int limit = 10;
        List<Conversation> conversations = Arrays.asList(testConversation);
        Page<Conversation> page = new PageImpl<>(conversations);

        when(conversationRepository.findAll(any(Pageable.class))).thenReturn(page);

        // When
        List<Conversation> result = historyService.getRecentConversations(limit);

        // Then
        assertEquals(1, result.size());
        assertEquals(testConversation, result.get(0));

        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(conversationRepository).findAll(pageableCaptor.capture());

        Pageable pageable = pageableCaptor.getValue();
        assertEquals(0, pageable.getPageNumber());
        assertEquals(limit, pageable.getPageSize());
        assertEquals(Sort.by("updatedAt").descending(), pageable.getSort());
    }

    @Test
    void testUpdateConversationMetadata() {
        // Given
        String conversationId = "test-conversation-id";
        Map<String, Object> newMetadata = Map.of("newKey", "newValue");

        testConversation.setMetadata(new HashMap<>(Map.of("existingKey", "existingValue")));

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(testConversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Conversation result = historyService.updateConversationMetadata(conversationId, newMetadata);

        // Then
        assertEquals("existingValue", result.getMetadata().get("existingKey"));
        assertEquals("newValue", result.getMetadata().get("newKey"));
        assertTrue(result.getUpdatedAt().isAfter(testTime.minusSeconds(1)));

        verify(conversationRepository).findById(conversationId);
        verify(conversationRepository).save(testConversation);
    }

    @Test
    void testUpdateConversationMetadata_ConversationNotFound() {
        // Given
        String conversationId = "non-existent-id";
        Map<String, Object> metadata = Map.of("key", "value");

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.empty());

        // When & Then
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class, () ->
            historyService.updateConversationMetadata(conversationId, metadata));

        assertEquals("Conversation not found: " + conversationId, exception.getMessage());
        verify(conversationRepository).findById(conversationId);
        verify(conversationRepository, never()).save(any(Conversation.class));
    }

    @Test
    void testUpdateConversationMetadata_NullExistingMetadata() {
        // Given
        String conversationId = "test-conversation-id";
        Map<String, Object> newMetadata = Map.of("key", "value");

        testConversation.setMetadata(null);

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(testConversation));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When
        Conversation result = historyService.updateConversationMetadata(conversationId, newMetadata);

        // Then
        assertNotNull(result.getMetadata());
        assertEquals("value", result.getMetadata().get("key"));
    }

    @Test
    void testDeleteConversation_Success() {
        // Given
        String conversationId = "test-conversation-id";

        when(conversationRepository.existsById(conversationId)).thenReturn(true);
        when(chatMessageRepository.deleteByConversationId(conversationId)).thenReturn(5);

        // When
        boolean result = historyService.deleteConversation(conversationId);

        // Then
        assertTrue(result);
        verify(conversationRepository).existsById(conversationId);
        verify(chatMessageRepository).deleteByConversationId(conversationId);
        verify(conversationRepository).deleteById(conversationId);
    }

    @Test
    void testDeleteConversation_NotFound() {
        // Given
        String conversationId = "non-existent-id";

        when(conversationRepository.existsById(conversationId)).thenReturn(false);

        // When
        boolean result = historyService.deleteConversation(conversationId);

        // Then
        assertFalse(result);
        verify(conversationRepository).existsById(conversationId);
        verify(chatMessageRepository, never()).deleteByConversationId(anyString());
        verify(conversationRepository, never()).deleteById(anyString());
    }

    @Test
    void testDeleteConversation_NullId() {
        // When
        boolean result = historyService.deleteConversation(null);

        // Then
        assertFalse(result);
        verify(conversationRepository, never()).existsById(anyString());
    }

    @Test
    void testSearchConversationsByTitle() {
        // Given
        String keyword = "test";

        Conversation conversation1 = Conversation.builder()
            .id("1")
            .title("Test Conversation")
            .build();

        Conversation conversation2 = Conversation.builder()
            .id("2")
            .title("Another Topic")
            .build();

        Conversation conversation3 = Conversation.builder()
            .id("3")
            .title("Testing Framework")
            .build();

        List<Conversation> allConversations = Arrays.asList(conversation1, conversation2, conversation3);
        when(conversationRepository.findAll()).thenReturn(allConversations);

        // When
        List<Conversation> result = historyService.searchConversationsByTitle(keyword);

        // Then
        assertEquals(2, result.size());
        assertTrue(result.contains(conversation1));
        assertTrue(result.contains(conversation3));
        assertFalse(result.contains(conversation2));
        verify(conversationRepository).findAll();
    }

    @Test
    void testSearchConversationsByTitle_CaseInsensitive() {
        // Given
        String keyword = "TEST";

        Conversation conversation = Conversation.builder()
            .id("1")
            .title("test conversation")
            .build();

        when(conversationRepository.findAll()).thenReturn(Arrays.asList(conversation));

        // When
        List<Conversation> result = historyService.searchConversationsByTitle(keyword);

        // Then
        assertEquals(1, result.size());
        assertEquals(conversation, result.get(0));
    }

    @Test
    void testSearchConversationsByTitle_NullTitle() {
        // Given
        String keyword = "test";

        Conversation conversationWithNullTitle = Conversation.builder()
            .id("1")
            .title(null)
            .build();

        when(conversationRepository.findAll()).thenReturn(Arrays.asList(conversationWithNullTitle));

        // When
        List<Conversation> result = historyService.searchConversationsByTitle(keyword);

        // Then
        assertTrue(result.isEmpty());
    }

    @Test
    void testDeleteAllConversations() {
        // Given
        Conversation conversation1 = Conversation.builder().id("1").build();
        Conversation conversation2 = Conversation.builder().id("2").build();
        List<Conversation> allConversations = Arrays.asList(conversation1, conversation2);

        when(conversationRepository.findAll()).thenReturn(allConversations);

        // When
        historyService.deleteAllConversations();

        // Then
        verify(conversationRepository).findAll();
        verify(chatMessageRepository).deleteByConversationId("1");
        verify(chatMessageRepository).deleteByConversationId("2");
        verify(conversationRepository).deleteAll();
    }

    @Test
    void testDeleteAllConversations_NoConversations() {
        // Given
        when(conversationRepository.findAll()).thenReturn(Collections.emptyList());

        // When
        historyService.deleteAllConversations();

        // Then
        verify(conversationRepository).findAll();
        verify(chatMessageRepository, never()).deleteByConversationId(anyString());
        verify(conversationRepository).deleteAll();
    }

    @Test
    void testAddMessage_UpdatesConversationTitle() {
        // Given
        String conversationId = "test-conversation-id";
        String longContent = "This is a very long message that should be truncated when used as conversation title because it exceeds fifty characters";

        Conversation conversationWithoutTitle = Conversation.builder()
            .id(conversationId)
            .title(null)
            .createdAt(testTime)
            .updatedAt(testTime)
            .metadata(new HashMap<>())
            .build();

        when(conversationRepository.findById(conversationId)).thenReturn(Optional.of(conversationWithoutTitle));
        when(conversationRepository.save(any(Conversation.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(chatMessageRepository.save(any(ChatMessage.class))).thenAnswer(invocation -> {
            ChatMessage message = invocation.getArgument(0);
            message.setId(String.valueOf(1L));
            return message;
        });

        // When
        historyService.addMessage(conversationId, MessageRole.USER, longContent, null);

        // Then
        ArgumentCaptor<Conversation> conversationCaptor = ArgumentCaptor.forClass(Conversation.class);
        verify(conversationRepository).save(conversationCaptor.capture());

        Conversation savedConversation = conversationCaptor.getValue();
        assertNotNull(savedConversation.getTitle());
        assertTrue(savedConversation.getTitle().length() <= 53); // 50 chars + "..."
        assertTrue(savedConversation.getTitle().endsWith("..."));
    }
}
