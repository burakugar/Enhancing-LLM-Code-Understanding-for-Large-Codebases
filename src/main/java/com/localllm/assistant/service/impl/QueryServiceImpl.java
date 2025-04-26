package com.localllm.assistant.service.impl;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import com.localllm.assistant.config.AsyncConfig;
import com.localllm.assistant.config.OllamaConfig;
import com.localllm.assistant.controller.dto.QueryResponse;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.history.impl.H2HistoryRepositoryImpl;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.llm.LlmClient;
import com.localllm.assistant.service.QueryService;
import com.localllm.assistant.vectorstore.VectorSearchResult;
import com.localllm.assistant.vectorstore.VectorStoreService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import lombok.RequiredArgsConstructor;

/**
 * Implementation of QueryService that manages the entire query processing workflow.
 * Coordinates between vector search, LLM generation, and history management.
 */
@Service
@RequiredArgsConstructor
public class QueryServiceImpl implements QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryServiceImpl.class);
    
    private static final int MAX_CONTEXT_SEGMENTS = 5; // Maximum code segments to include in context
    private static final double MIN_SIMILARITY_SCORE = 0.5; // Minimum similarity score for inclusion
    
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final LlmClient llmClient;
    private final H2HistoryRepositoryImpl historyRepository;
    private final OllamaConfig ollamaConfig;

    @Override
    @Async(AsyncConfig.TASK_EXECUTOR_EMBEDDING)
    public CompletableFuture<QueryResponse> processQuery(String query, String conversationId) {
        log.info("Processing query: '{}' for conversation ID: {}", 
                query.length() > 50 ? query.substring(0, 47) + "..." : query, 
                conversationId != null ? conversationId : "new");
        
        // Step 1: Create or retrieve conversation
        return getOrCreateConversation(conversationId)
                // Step 2: Get conversation history
                .thenCompose(conversation -> {
                    return getConversationHistory(conversation.getId())
                            .thenApply(messages -> Map.entry(conversation, messages));
                })
                // Step 3: Generate embedding for query and find relevant context
                .thenCompose(conversationAndHistory -> {
                    Conversation conversation = conversationAndHistory.getKey();
                    List<ChatMessage> history = conversationAndHistory.getValue();
                    
                    return embeddingService.generateEmbeddingAsync(query)
                            .thenCompose(embedding -> 
                                    vectorStoreService.searchSimilarAsync(embedding, MAX_CONTEXT_SEGMENTS, MIN_SIMILARITY_SCORE)
                                            .thenApply(results -> Map.entry(conversation, Map.entry(history, results)))
                            );
                })
                // Step 4: Save user query to history
                .thenCompose(conversationHistoryAndContext -> {
                    Conversation conversation = conversationHistoryAndContext.getKey();
                    List<ChatMessage> history = conversationHistoryAndContext.getValue().getKey();
                    List<VectorSearchResult> contextResults = conversationHistoryAndContext.getValue().getValue();
                    
                    // Add user message to history
                    ChatMessage userMessage = createUserMessage(conversation.getId(), query);
                    return saveMessage(userMessage)
                            .thenApply(savedMessage -> Map.entry(conversation, Map.entry(history, contextResults)));
                })
                // Step 5: Generate LLM response with context
                .thenCompose(conversationHistoryAndContext -> {
                    Conversation conversation = conversationHistoryAndContext.getKey();
                    List<ChatMessage> history = conversationHistoryAndContext.getValue().getKey();
                    List<VectorSearchResult> contextResults = conversationHistoryAndContext.getValue().getValue();
                    
                    List<ChatMessage> messagesWithContext = createMessagesWithContext(history, contextResults, query);
                    
                    return llmClient.generateChatCompletionAsync(
                            messagesWithContext, 
                            ollamaConfig.getChatModel(), 
                            0.7)
                            .thenApply(llmResponse -> Map.entry(conversation, Map.entry(contextResults, llmResponse)));
                })
                // Step 6: Save assistant response to history
                .thenCompose(conversationContextAndResponse -> {
                    Conversation conversation = conversationContextAndResponse.getKey();
                    List<VectorSearchResult> contextResults = conversationContextAndResponse.getValue().getKey();
                    String llmResponse = conversationContextAndResponse.getValue().getValue();
                    
                    // Create metadata with sources
                    Map<String, Object> metadata = new HashMap<>();
                    if (!contextResults.isEmpty()) {
                        List<Map<String, Object>> sources = contextResults.stream()
                                .map(result -> {
                                    Map<String, Object> source = new HashMap<>();
                                    source.put("filePath", result.getSegment().getRelativeFilePath());
                                    source.put("startLine", result.getSegment().getStartLine());
                                    source.put("endLine", result.getSegment().getEndLine());
                                    source.put("score", result.getScore());
                                    source.put("content", result.getSegment().getContent());
                                    return source;
                                })
                                .collect(Collectors.toList());
                        metadata.put("sources", sources);
                    }
                    
                    ChatMessage assistantMessage = createAssistantMessage(conversation.getId(), llmResponse, metadata);
                    return saveMessage(assistantMessage)
                            .thenApply(savedMessage -> buildResponse(llmResponse, contextResults, conversation.getId()));
                });
    }

    /**
     * Gets an existing conversation or creates a new one.
     */
    private CompletableFuture<Conversation> getOrCreateConversation(String conversationId) {
        CompletableFuture<Conversation> future = new CompletableFuture<>();
        
        try {
            if (conversationId != null && !conversationId.trim().isEmpty()) {
                // Retrieve existing conversation
                historyRepository.findById(conversationId)
                        .ifPresentOrElse(
                                conversation -> future.complete(conversation),
                                () -> {
                                    // Create new if not found (ID provided but not valid)
                                    log.warn("Conversation ID {} not found, creating new conversation", conversationId);
                                    Conversation newConversation = historyRepository.createConversation(null, null, null);
                                    future.complete(newConversation);
                                }
                        );
            } else {
                // Create new conversation
                Conversation newConversation = historyRepository.createConversation(null, null, null);
                future.complete(newConversation);
            }
        } catch (Exception e) {
            log.error("Error getting/creating conversation: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        
        return future;
    }

    /**
     * Gets the conversation history messages.
     */
    private CompletableFuture<List<ChatMessage>> getConversationHistory(String conversationId) {
        CompletableFuture<List<ChatMessage>> future = new CompletableFuture<>();
        
        try {
            List<ChatMessage> messages = historyRepository.getConversationMessages(conversationId);
            future.complete(messages);
        } catch (Exception e) {
            log.error("Error retrieving conversation history: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        
        return future;
    }

    /**
     * Creates a user message entity.
     */
    private ChatMessage createUserMessage(String conversationId, String content) {
        return ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role(MessageRole.USER)
                .content(content)
                .timestamp(LocalDateTime.now())
                .build();
    }

    /**
     * Creates an assistant message entity.
     */
    private ChatMessage createAssistantMessage(String conversationId, String content, Map<String, Object> metadata) {
        return ChatMessage.builder()
                .id(UUID.randomUUID().toString())
                .conversationId(conversationId)
                .role(MessageRole.ASSISTANT)
                .content(content)
                .timestamp(LocalDateTime.now())
                .metadata(metadata)
                .build();
    }

    /**
     * Saves a message to the history repository.
     */
    private CompletableFuture<ChatMessage> saveMessage(ChatMessage message) {
        CompletableFuture<ChatMessage> future = new CompletableFuture<>();
        
        try {
            ChatMessage savedMessage = historyRepository.addMessage(
                    message.getConversationId(),
                    message.getRole(),
                    message.getContent(),
                    message.getMetadata());
            future.complete(savedMessage);
        } catch (Exception e) {
            log.error("Error saving message: {}", e.getMessage(), e);
            future.completeExceptionally(e);
        }
        
        return future;
    }

    /**
     * Creates the chat messages including context for the LLM.
     */
    private List<ChatMessage> createMessagesWithContext(
            List<ChatMessage> history,
            List<VectorSearchResult> contextResults,
            String currentQuery) {
        
        List<ChatMessage> messagesForLlm = new ArrayList<>();
        
        // Add system message with instruction
        messagesForLlm.add(ChatMessage.builder()
                .role(MessageRole.SYSTEM)
                .content(createSystemPrompt(contextResults))
                .build());
        
        // Add recent conversation history (limit to last 10 messages to avoid context window issues)
        if (!history.isEmpty()) {
            int startIdx = Math.max(0, history.size() - 10);
            messagesForLlm.addAll(history.subList(startIdx, history.size()));
        }
        
        // Add current query if not in history already
        if (history.isEmpty() || !history.get(history.size() - 1).getRole().equals(MessageRole.USER)) {
            messagesForLlm.add(ChatMessage.builder()
                    .role(MessageRole.USER)
                    .content(currentQuery)
                    .build());
        }
        
        return messagesForLlm;
    }

    /**
     * Creates the system prompt with context.
     */
    private String createSystemPrompt(List<VectorSearchResult> contextResults) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("You are a helpful coding assistant. ");
        prompt.append("Your task is to help the user with their coding questions based on the context from their codebase. ");
        prompt.append("Keep answers clear, concise, and focused on the code context provided.");
        
        if (!contextResults.isEmpty()) {
            prompt.append("\n\nHere are some relevant code snippets from the codebase to help with your answer:\n\n");
            
            for (int i = 0; i < contextResults.size(); i++) {
                VectorSearchResult result = contextResults.get(i);
                prompt.append("SNIPPET ").append(i + 1).append(" (score: ").append(String.format("%.2f", result.getScore())).append("):\n");
                prompt.append("File: ").append(result.getSegment().getRelativeFilePath()).append("\n");
                prompt.append("Lines: ").append(result.getSegment().getStartLine()).append("-").append(result.getSegment().getEndLine()).append("\n");
                prompt.append("```java\n");
                prompt.append(result.getSegment().getContent()).append("\n");
                prompt.append("```\n\n");
            }
        }
        
        return prompt.toString();
    }

    /**
     * Builds the response DTO.
     */
    private QueryResponse buildResponse(String llmResponse, List<VectorSearchResult> contextResults, String conversationId) {
        List<QueryResponse.SourceReference> sources = contextResults.stream()
                .map(result -> QueryResponse.SourceReference.builder()
                        .filePath(result.getSegment().getRelativeFilePath())
                        .startLine(result.getSegment().getStartLine())
                        .endLine(result.getSegment().getEndLine())
                        .snippet(result.getSegment().getContent())
                        .score(result.getScore())
                        .build())
                .collect(Collectors.toList());
        
        return QueryResponse.builder()
                .answer(llmResponse)
                .sources(sources)
                .conversationId(conversationId)
                .build();
    }
} 
