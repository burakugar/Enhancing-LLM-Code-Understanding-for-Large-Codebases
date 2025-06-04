package com.localllm.assistant.service.impl;

import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.config.OllamaConfig;
import com.localllm.assistant.controller.dto.QueryRequest;
import com.localllm.assistant.controller.dto.QueryResponse;
import com.localllm.assistant.embedding.EmbeddingService;
import com.localllm.assistant.history.model.ChatMessage;
import com.localllm.assistant.history.model.Conversation;
import com.localllm.assistant.history.model.MessageRole;
import com.localllm.assistant.llm.LlmClient;
import com.localllm.assistant.parser.model.CodeSegment;
import com.localllm.assistant.service.HistoryService;
import com.localllm.assistant.service.QueryService;
import com.localllm.assistant.service.ReRankerService;
import com.localllm.assistant.vectorstore.VectorStoreService;
import com.localllm.assistant.vectorstore.model.VectorSearchResult;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class QueryServiceImpl implements QueryService {

    private static final Logger log = LoggerFactory.getLogger(QueryServiceImpl.class);

    // === AGGRESSIVE CONTEXT CONFIGURATION ===
    @Value("${query.defaultTemperature:0.3}")
    private double defaultTemperature;

    @Value("${query.defaultMaxContextSegments:15}")
    private int defaultMaxContextSegments;

    @Value("${query.defaultMinSimilarityScore:0.50}")
    private double defaultMinSimilarityScore;

    @Value("${query.defaultFetchKMultiplier:3.0}")
    private double defaultFetchKMultiplier;

    @Value("${query.defaultFetchKBonus:25}")
    private int defaultFetchKBonus;

    @Value("${query.contextUtilizationRatio:0.85}")
    private double contextUtilizationRatio;

    @Value("${reranker.enabled:false}")
    private boolean rerankerEnabled;

    @Value("${reranker.defaultTopN:8}")
    private int defaultRerankerTopN;

    @Value("${reranker.fetchMultiplierWhenEnabled:2.5}")
    private double rerankerFetchMultiplier;

    @Value("${reranker.model}")
    private String defaultRerankerModelName;

    @Value("${ollama.chatModelMaxPromptTokens:8000}")
    private int llmMaxPromptTokens;

    @Value("${ollama.chatModelDefaultNumPredict:1024}")
    private int defaultLlmNumPredict;

    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final LlmClient llmClient;
    private final HistoryService historyService;
    private final OllamaConfig ollamaConfig;
    private final ChromaDBConfig chromaDBConfig;
    private final ReRankerService reRankerService;

    @Override
    public CompletableFuture<QueryResponse> processQuery(QueryRequest queryRequest) {
        String chatModelToUse = StringUtils.hasText(queryRequest.getModelName()) ?
            queryRequest.getModelName() : ollamaConfig.getChatModel();

        String rerankerModelToUse = StringUtils.hasText(queryRequest.getRerankerModelName()) ?
            queryRequest.getRerankerModelName() : this.defaultRerankerModelName;

        boolean useReRankerActual = Optional.ofNullable(queryRequest.getUseReRanker()).orElse(this.rerankerEnabled);

        int maxContextSegments = Optional.ofNullable(queryRequest.getMaxContextSegments())
            .orElse(useReRankerActual ? Math.max(defaultMaxContextSegments, 20) : defaultMaxContextSegments);

        int reRankerTopN = Optional.ofNullable(queryRequest.getReRankerTopN())
            .orElse(useReRankerActual ? Math.max(defaultRerankerTopN, maxContextSegments) : defaultRerankerTopN);

        log.info("Processing query with AGGRESSIVE CONTEXT. Chat Model: {}, Reranker: {}, MaxSegments: {}, ReRankTopN: {}",
            chatModelToUse, useReRankerActual ? rerankerModelToUse : "N/A", maxContextSegments, reRankerTopN);

        return processQueryInternal(
            queryRequest.getQuery(),
            queryRequest.getConversationId(),
            Optional.ofNullable(queryRequest.getTemperature()).orElse(defaultTemperature),
            maxContextSegments,
            Optional.ofNullable(queryRequest.getMinSimilarityScore()).orElse(defaultMinSimilarityScore),
            useReRankerActual,
            reRankerTopN,
            Optional.ofNullable(queryRequest.getLlmMaxNewTokens()).orElse(defaultLlmNumPredict),
            chatModelToUse,
            rerankerModelToUse
        );
    }

    @Deprecated
    @Override
    public CompletableFuture<QueryResponse> processQuery(String query, String conversationId) {
        return processQueryInternal(query, conversationId, defaultTemperature, defaultMaxContextSegments,
            defaultMinSimilarityScore, rerankerEnabled, defaultRerankerTopN, defaultLlmNumPredict,
            ollamaConfig.getChatModel(), this.defaultRerankerModelName);
    }

    private CompletableFuture<QueryResponse> processQueryInternal(String query, String requestConversationId,
                                                                  double temperature, int maxContextSegments,
                                                                  double minSimilarityScore, boolean useReRankerFlag,
                                                                  int reRankerTopN, int llmNumPredict, String chatModelName,
                                                                  String rerankerModelName) {

        long startTime = System.currentTimeMillis();
        final String currentRequestConversationId = (requestConversationId == null || requestConversationId.isBlank()) ?
            UUID.randomUUID().toString() : requestConversationId;

        log.info("PERF_LOG: Query processing started for ConvID: {}. Timestamp: {}", currentRequestConversationId, startTime);
        log.info("AGGRESSIVE CONTEXT MODE - ConvID: {}, MaxSeg: {}, MinScore: {}, UseReRanker: {}, TopN: {}",
            currentRequestConversationId, maxContextSegments, minSimilarityScore, useReRankerFlag, reRankerTopN);

        CompletableFuture<Conversation> conversationFuture = CompletableFuture.supplyAsync(() ->
            historyService.getConversation(currentRequestConversationId)
                .orElseGet(() -> {
                    String title = query.substring(0, Math.min(query.length(), 50)) + (query.length() > 50 ? "..." : "");
                    return historyService.createConversation(currentRequestConversationId, title, null, null);
                })
        );

        return conversationFuture.thenCompose(conv -> {
                ChatMessage userMessage = ChatMessage.builder()
                    .conversationId(conv.getId())
                    .role(MessageRole.USER)
                    .content(query)
                    .timestamp(LocalDateTime.now())
                    .build();
                historyService.addMessage(userMessage.getConversationId(), userMessage.getRole(), userMessage.getContent(), userMessage.getMetadata());

                CompletableFuture<List<ChatMessage>> historyFuture = CompletableFuture.supplyAsync(() ->
                    historyService.getRecentMessagesForConversation(conv.getId(), 15) // INCREASED history
                );

                log.info("PERF_LOG: Starting query embedding for ConvID: {}. Timestamp: {}", currentRequestConversationId, System.currentTimeMillis());
                CompletableFuture<List<Float>> queryEmbeddingFuture = embeddingService.generateEmbeddingAsync(query);

                return historyFuture.thenCombine(queryEmbeddingFuture, (history, queryEmbedding) -> {
                    log.info("PERF_LOG: Query embedding completed for ConvID: {}. Timestamp: {}", currentRequestConversationId,
                        System.currentTimeMillis());
                    if (queryEmbedding == null || queryEmbedding.isEmpty()) {
                        log.error("Failed to generate query embedding for query: {}", query);
                        throw new RuntimeException("Failed to generate query embedding.");
                    }

                    // AGGRESSIVE FETCHING STRATEGY
                    int initialFetchK;
                    if (useReRankerFlag) {
                        // When using reranker, fetch MUCH more candidates
                        initialFetchK = (int) (reRankerTopN * rerankerFetchMultiplier * defaultFetchKMultiplier) + defaultFetchKBonus;
                        initialFetchK = Math.max(initialFetchK, 50); // Minimum 50 candidates for reranking
                        log.info("RERANKER MODE: Fetching {} candidates to rerank down to {} final segments", initialFetchK, reRankerTopN);
                    } else {
                        // Even without reranker, fetch more for better selection
                        initialFetchK = (int) (maxContextSegments * defaultFetchKMultiplier) + defaultFetchKBonus;
                        initialFetchK = Math.max(initialFetchK, 30); // Minimum 30 candidates
                        log.info("DIRECT MODE: Fetching {} candidates to select {} final segments", initialFetchK, maxContextSegments);
                    }

                    int finalInitialFetchK = initialFetchK;
                    log.info("PERF_LOG: Starting vector search for ConvID: {}. Timestamp: {}", currentRequestConversationId, System.currentTimeMillis());
                    return vectorStoreService.searchSimilarAsync(
                        queryEmbedding,
                        initialFetchK,
                        minSimilarityScore
                    ).thenApply(vectorResults -> {
                        log.info("PERF_LOG: Vector search completed for ConvID: {}. Timestamp: {}", currentRequestConversationId,
                            System.currentTimeMillis());
                        log.info("Vector search returned {} results (requested: {}, min_score: {})",
                            vectorResults.size(), finalInitialFetchK, minSimilarityScore);
                        return Map.entry(history, vectorResults);
                    });
                }).thenCompose(Function.identity());
            })
            .thenCompose(historyAndVectorResults -> {
                List<ChatMessage> history = historyAndVectorResults.getKey();
                List<VectorSearchResult> initialContext = historyAndVectorResults.getValue();

                if (useReRankerFlag && !initialContext.isEmpty()) {
                    log.info("PERF_LOG: Starting re-ranking for ConvID: {}. Timestamp: {}", currentRequestConversationId, System.currentTimeMillis());
                    log.info("RERANKING {} candidates down to top {} with model: {}",
                        initialContext.size(), reRankerTopN, rerankerModelName);
                    return reRankerService.reRankAsync(query, initialContext, reRankerTopN, rerankerModelName)
                        .thenApply(reRankedResults -> {
                            log.info("PERF_LOG: Re-ranking completed for ConvID: {}. Timestamp: {}", currentRequestConversationId,
                                System.currentTimeMillis());
                            log.info("RERANKED: {} results received (requested: {})", reRankedResults.size(), reRankerTopN);
                            return Map.entry(history, reRankedResults);
                        });
                } else {
                    // Even without reranker, be more aggressive about context selection
                    List<VectorSearchResult> finalContext = initialContext.stream()
                        .sorted(Comparator.comparingDouble(VectorSearchResult::getScore).reversed())
                        .limit(maxContextSegments)
                        .collect(Collectors.toList());
                    log.info("NO RERANKING: Selected top {} from {} candidates", finalContext.size(), initialContext.size());
                    return CompletableFuture.completedFuture(Map.entry(history, finalContext));
                }
            })
            .thenCompose(historyAndFinalContext -> {
                List<ChatMessage> history = historyAndFinalContext.getKey();
                List<VectorSearchResult> codeContext = historyAndFinalContext.getValue();

                List<ChatMessage> promptMessages = buildMaximumContextPrompt(query, history, codeContext);

                if (log.isInfoEnabled()) {
                    int estimatedTokens = promptMessages.stream()
                        .mapToInt(msg -> estimateTokens(msg.getContent()))
                        .sum();
                    log.info("AGGRESSIVE CONTEXT PROMPT: {} messages, ~{} tokens, {} code segments",
                        promptMessages.size(), estimatedTokens, codeContext.size());
                }

                log.info("PERF_LOG: Starting LLM call for ConvID: {}. Timestamp: {}", currentRequestConversationId, System.currentTimeMillis());
                return llmClient.generateChatCompletionAsync(
                    promptMessages,
                    chatModelName,
                    temperature,
                    llmNumPredict
                ).thenApply(llmResponse -> {
                    log.info("PERF_LOG: LLM call completed for ConvID: {}. Timestamp: {}", currentRequestConversationId, System.currentTimeMillis());
                    return Map.entry(llmResponse, codeContext);
                });
            })
            .thenCompose(responseAndContext -> {
                String llmResponseContent = responseAndContext.getKey();
                List<VectorSearchResult> usedContext = responseAndContext.getValue();

                ChatMessage assistantMessage = ChatMessage.builder()
                    .conversationId(currentRequestConversationId)
                    .role(MessageRole.ASSISTANT)
                    .content(llmResponseContent)
                    .timestamp(LocalDateTime.now())
                    .metadata(Map.of(
                        "codeReferences", extractSourceMetadata(usedContext),
                        "chatModelUsed", chatModelName,
                        "rerankerModelUsed", useReRankerFlag ? rerankerModelName : "N/A",
                        "contextSegmentsUsed", usedContext.size(),
                        "aggressiveContextMode", true
                    ))
                    .build();
                historyService.addMessage(assistantMessage.getConversationId(), assistantMessage.getRole(), assistantMessage.getContent(),
                    assistantMessage.getMetadata());

                QueryResponse responseDto = QueryResponse.builder()
                    .answer(llmResponseContent)
                    .conversationId(currentRequestConversationId)
                    .sources(extractSourceMetadata(usedContext))
                    .build();

                long endTime = System.currentTimeMillis();
                log.info("PERF_LOG: Query processing finished for ConvID: {}. Total time: {}ms. Timestamp: {}",
                    currentRequestConversationId, (endTime - startTime), endTime);

                return CompletableFuture.completedFuture(responseDto);
            })
            .exceptionally(ex -> {
                long errorTime = System.currentTimeMillis();
                log.error("PERF_LOG: Error processing query for conversation {}. Timestamp: {}", currentRequestConversationId, errorTime, ex);
                Throwable cause = (ex instanceof CompletionException && ex.getCause() != null) ? ex.getCause() : ex;
                return QueryResponse.builder()
                    .answer("Error processing query: " + cause.getMessage())
                    .conversationId(currentRequestConversationId)
                    .sources(Collections.emptyList())
                    .build();
            });
    }

    private int estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        return (int) (text.length() / 3.5);
    }

    private List<ChatMessage> buildMaximumContextPrompt(String currentQuery, List<ChatMessage> history, List<VectorSearchResult> codeContext) {
        List<ChatMessage> finalPromptMessages = new ArrayList<>();
        int currentTokenCount = 0;
        int maxTokens = this.llmMaxPromptTokens;
        int maxContextTokens = (int) (maxTokens * contextUtilizationRatio); // Use 85% for context

        // ===== MINIMAL HISTORY DEBUGGING =====
        log.info("HISTORY_DEBUG: Received history with {} messages", history != null ? history.size() : 0);

        boolean isGeneralQuery = currentQuery.matches("(?i)(hello|hi|how are you|what's up|hey).*") ||
            (codeContext == null || codeContext.isEmpty());

        String systemPromptContent;
        if (isGeneralQuery && (codeContext == null || codeContext.isEmpty())) {
            systemPromptContent = "You are a helpful and friendly AI assistant specialized in code analysis. " +
                "Engage in conversation and offer to help with code-related questions.";
        } else {
            systemPromptContent =
                "You are an expert Java code analyst with deep understanding of software architecture, design patterns, and best practices. " +
                    "Analyze the provided code context comprehensively. When explaining code:\n" +
                    "1. Identify the purpose and responsibility of each component\n" +
                    "2. Explain relationships between classes/methods\n" +
                    "3. Point out design patterns, architectural decisions\n" +
                    "4. Highlight important implementation details\n" +
                    "5. Reference specific code snippets using ```java blocks\n" +
                    "6. Be thorough but concise in your explanations\n" +
                    "Use the extensive code context provided to give detailed, accurate answers.";
        }

        ChatMessage systemMessage = ChatMessage.builder()
            .role(MessageRole.SYSTEM)
            .content(systemPromptContent)
            .build();
        finalPromptMessages.add(systemMessage);
        currentTokenCount += estimateTokens(systemMessage.getContent());

        ChatMessage userQueryMessage = ChatMessage.builder().role(MessageRole.USER).content(currentQuery).build();
        int userQueryTokens = estimateTokens(userQueryMessage.getContent());
        int reservedTokens = currentTokenCount + userQueryTokens;

        // ===== MAXIMUM CODE CONTEXT PACKING =====
        List<ChatMessage> contextMessages = new ArrayList<>();
        if (codeContext != null && !codeContext.isEmpty()) {
            StringBuilder contextStr = new StringBuilder();
            contextStr.append("=== COMPREHENSIVE CODE CONTEXT ===\n");
            contextStr.append("Analyze this extensive code context to provide detailed answers:\n\n");

            int contextTokensUsed = 0;
            int segmentsIncluded = 0;

            for (int i = 0; i < codeContext.size(); i++) {
                VectorSearchResult result = codeContext.get(i);
                CodeSegment segment = result.getSegment();
                if (segment == null) {
                    continue;
                }

                StringBuilder snippetBuilder = new StringBuilder();
                snippetBuilder.append("### Code Segment ").append(i + 1).append(" ###\n");
                snippetBuilder.append("📁 File: ").append(segment.getRelativeFilePath()).append("\n");

                String fqn = (segment.getMetadata() != null) ? (String) segment.getMetadata().get("fqn") : null;
                if (fqn != null && !fqn.isEmpty()) {
                    snippetBuilder.append("🏷️  FQN: ").append(fqn).append("\n");
                }

                snippetBuilder.append("📍 Lines: ").append(segment.getStartLine()).append("-").append(segment.getEndLine()).append("\n");
                snippetBuilder.append("⭐ Score: ").append(String.format("%.3f", result.getScore())).append("\n");
                snippetBuilder.append("```java\n").append(segment.getContent() != null ? segment.getContent().trim() : "// Content not available")
                    .append("\n```\n\n");

                int snippetTokens = estimateTokens(snippetBuilder.toString());

                // Check if we can fit this snippet
                if (reservedTokens + contextTokensUsed + snippetTokens <= maxContextTokens) {
                    contextStr.append(snippetBuilder);
                    contextTokensUsed += snippetTokens;
                    segmentsIncluded++;
                } else {
                    log.info("CONTEXT LIMIT: Included {} of {} segments ({} tokens). Segment {} would exceed limit.",
                        segmentsIncluded, codeContext.size(), contextTokensUsed, i + 1);
                    break;
                }
            }

            contextStr.append("=== END CODE CONTEXT ===\n");
            contextStr.append(String.format("📊 Context Summary: %d code segments included, ~%d tokens used\n",
                segmentsIncluded, contextTokensUsed));

            if (contextTokensUsed > 0) {
                contextMessages.add(ChatMessage.builder()
                    .role(MessageRole.SYSTEM)
                    .content(contextStr.toString())
                    .build());
                log.info("PACKED CONTEXT: {} segments, {} tokens ({}% of available context space)",
                    segmentsIncluded, contextTokensUsed,
                    Math.round((double) contextTokensUsed / maxContextTokens * 100));
            }
        }

        // ===== MINIMAL HISTORY PROCESSING (CODE CONTEXT PRIORITY) =====
        int usedTokens = reservedTokens + contextMessages.stream().mapToInt(m -> estimateTokens(m.getContent())).sum();
        int remainingTokens = maxTokens - usedTokens;

        // STRICT: Only use 200-300 tokens max for history, prioritize code context
        int historyTokenBudget = Math.min(300, Math.max(0, remainingTokens / 4));

        log.info("HISTORY_DEBUG: MINIMAL MODE - Used: {}, Remaining: {}, History Budget: {} tokens (STRICT LIMIT)",
            usedTokens, remainingTokens, historyTokenBudget);

        List<ChatMessage> historyToAdd = new ArrayList<>();

        if (history != null && !history.isEmpty() && historyTokenBudget > 50) {
            // Filter out the current user message
            List<ChatMessage> filteredHistory = history.stream()
                .filter(msg -> !(msg.getRole() == MessageRole.USER && msg.getContent().equals(currentQuery)))
                .collect(Collectors.toList());

            log.info("HISTORY_DEBUG: After filtering current query, {} messages remain", filteredHistory.size());

            // MINIMAL STRATEGY: Only include the most recent USER question for context
            // Skip assistant responses entirely to save space for code
            List<ChatMessage> recentUserQuestions = filteredHistory.stream()
                .filter(msg -> msg.getRole() == MessageRole.USER)
                .sorted(Comparator.comparing(ChatMessage::getTimestamp).reversed())
                .limit(2) // Maximum 2 recent user questions
                .collect(Collectors.toList());

            int historyTokensUsed = 0;

            for (ChatMessage userMsg : recentUserQuestions) {
                int userMsgTokens = estimateTokens(userMsg.getContent());

                if (historyTokensUsed + userMsgTokens <= historyTokenBudget) {
                    historyToAdd.add(userMsg);
                    historyTokensUsed += userMsgTokens;
                    log.info("HISTORY_DEBUG: ✅ Added user question: '{}...' ({} tokens)",
                        userMsg.getContent().substring(0, Math.min(30, userMsg.getContent().length())), userMsgTokens);
                } else {
                    log.info("HISTORY_DEBUG: ❌ Skipping user question - would exceed strict budget");
                    break;
                }
            }

            // Sort chronologically
            historyToAdd.sort(Comparator.comparing(ChatMessage::getTimestamp));

            log.info("HISTORY_DEBUG: MINIMAL RESULT - Added {} user questions using {} tokens (budget: {})",
                historyToAdd.size(), historyTokensUsed, historyTokenBudget);
        } else {
            log.info("HISTORY_DEBUG: SKIPPING HISTORY - insufficient budget or no history (budget: {})", historyTokenBudget);
        }

        // Assemble final prompt
        finalPromptMessages.addAll(historyToAdd);
        finalPromptMessages.addAll(contextMessages);
        finalPromptMessages.add(userQueryMessage);

        // Final token count and logging
        int finalTokenCount = finalPromptMessages.stream()
            .mapToInt(msg -> estimateTokens(msg.getContent()))
            .sum();

        log.info("FINAL PROMPT: {} messages, {} tokens ({}% of limit), {} code segments, {} history messages",
            finalPromptMessages.size(), finalTokenCount,
            Math.round((double) finalTokenCount / maxTokens * 100),
            codeContext != null ? codeContext.size() : 0,
            historyToAdd.size());

        // ===== DETAILED PROMPT BREAKDOWN =====
        int historyTokens = historyToAdd.stream().mapToInt(m -> estimateTokens(m.getContent())).sum();
        int contextTokens = contextMessages.stream().mapToInt(m -> estimateTokens(m.getContent())).sum();

        log.info("PROMPT_BREAKDOWN: System={} tokens, History={} tokens, Context={} tokens, User={} tokens",
            estimateTokens(systemMessage.getContent()), historyTokens, contextTokens, userQueryTokens);

        return finalPromptMessages;
    }

    private List<QueryResponse.SourceReference> extractSourceMetadata(List<VectorSearchResult> results) {
        if (results == null) {
            return Collections.emptyList();
        }
        return results.stream()
            .filter(result -> result != null && result.getSegment() != null)
            .map(result -> QueryResponse.SourceReference.builder()
                .filePath(result.getSegment().getRelativeFilePath())
                .startLine(result.getSegment().getStartLine())
                .endLine(result.getSegment().getEndLine())
                .snippet(result.getSegment().getContent())
                .score(result.getScore())
                .build())
            .collect(Collectors.toList());
    }
}
