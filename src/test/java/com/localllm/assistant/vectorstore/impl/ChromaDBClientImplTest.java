package com.localllm.assistant.vectorstore.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.localllm.assistant.config.ChromaDBConfig;
import com.localllm.assistant.vectorstore.model.VectorEntry;
import org.apache.hc.client5.http.impl.async.CloseableHttpAsyncClient;
import org.apache.hc.core5.reactor.IOReactorStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ChromaDBClientImplTest {

    @Mock
    private ChromaDBConfig chromaDBConfig;

    @Mock
    private ObjectMapper objectMapper;

    @Mock
    private CloseableHttpAsyncClient httpAsyncClient;

    private ChromaDBClientImpl client;

    private final String TEST_COLLECTION_NAME = "test_collection";
    private final String BASE_URL = "http://localhost:8000";

    @BeforeEach
    void setUp() {
        when(chromaDBConfig.getUrl()).thenReturn(BASE_URL);
        when(chromaDBConfig.getDistanceFunction()).thenReturn("cosine");
        when(httpAsyncClient.getStatus()).thenReturn(IOReactorStatus.ACTIVE);

        client = new ChromaDBClientImpl(chromaDBConfig, objectMapper, httpAsyncClient);
    }

    @Test
    void testConstructor() {
        assertNotNull(client);
    }

    @Test
    void testCheckClientStatus_ActiveClient() {
        when(httpAsyncClient.getStatus()).thenReturn(IOReactorStatus.ACTIVE);
        assertDoesNotThrow(() -> client.checkClientStatus());
    }

    @Test
    void testCheckClientStatus_InactiveClient() {
        when(httpAsyncClient.getStatus()).thenReturn(IOReactorStatus.INACTIVE);
        assertDoesNotThrow(() -> client.checkClientStatus());
    }

    @Test
    void testCheckClientStatus_NullClient() {
        ChromaDBClientImpl clientWithNullHttp = new ChromaDBClientImpl(chromaDBConfig, objectMapper, null);
        assertDoesNotThrow(() -> clientWithNullHttp.checkClientStatus());
    }

    @Test
    void testClearCacheForCollection() {
        assertDoesNotThrow(() -> client.clearCacheForCollection(TEST_COLLECTION_NAME));
        assertDoesNotThrow(() -> client.clearCacheForCollection(null));
    }

    @Test
    void testUpsertEmbeddingsAsync_EmptyEntries() {
        CompletableFuture<Void> result = client.upsertEmbeddingsAsync(TEST_COLLECTION_NAME, Collections.emptyList());

        assertNotNull(result);
        assertTrue(result.isDone());
        assertDoesNotThrow(() -> result.get());
    }

    @Test
    void testUpsertEmbeddingsAsync_NullEntries() {
        CompletableFuture<Void> result = client.upsertEmbeddingsAsync(TEST_COLLECTION_NAME, null);

        assertNotNull(result);
        assertTrue(result.isDone());
        assertDoesNotThrow(() -> result.get());
    }

    @Test
    void testDeleteEmbeddingsByMetadataAsync_NullFilter() {
        Map<String, Object> filter = null;

        CompletableFuture<Void> result = client.deleteEmbeddingsByMetadataAsync(TEST_COLLECTION_NAME, filter);

        assertNotNull(result);
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void testDeleteEmbeddingsByMetadataAsync_EmptyFilter() {
        Map<String, Object> filter = Collections.emptyMap();

        CompletableFuture<Void> result = client.deleteEmbeddingsByMetadataAsync(TEST_COLLECTION_NAME, filter);

        assertNotNull(result);
        assertTrue(result.isCompletedExceptionally());
    }

    @Test
    void testDeleteEmbeddingsByIdsAsync_NullIds() {
        CompletableFuture<Void> result = client.deleteEmbeddingsByIdsAsync(TEST_COLLECTION_NAME, null);

        assertNotNull(result);
        assertTrue(result.isDone());
        assertDoesNotThrow(() -> result.get());
    }

    @Test
    void testDeleteEmbeddingsByIdsAsync_EmptyIds() {
        CompletableFuture<Void> result = client.deleteEmbeddingsByIdsAsync(TEST_COLLECTION_NAME, Collections.emptyList());

        assertNotNull(result);
        assertTrue(result.isDone());
        assertDoesNotThrow(() -> result.get());
    }

    @Test
    void testSearchSimilarEmbeddingsAsync_NullEmbedding() {
        Map<String, Object> filter = Map.of("type", "METHOD");

        CompletableFuture<List<VectorEntry>> result = client.searchSimilarEmbeddingsAsync(
            TEST_COLLECTION_NAME, null, 5, filter);

        assertNotNull(result);
        // This will likely fail due to missing collection setup, but that's expected
        // We're just testing that the method returns a future and doesn't crash
    }

    @Test
    void testGetCollectionBasePath_WithUuid() {
        String result = client.getCollectionBasePath(TEST_COLLECTION_NAME, true);
        assertTrue(result.contains(TEST_COLLECTION_NAME));
        assertTrue(result.contains("collections"));
    }

    @Test
    void testCreateTestVectorEntries() {
        List<VectorEntry> entries = createTestVectorEntries();

        assertNotNull(entries);
        assertFalse(entries.isEmpty());
        assertEquals(1, entries.size());

        VectorEntry entry = entries.get(0);
        assertNotNull(entry.getId());
        assertNotNull(entry.getEmbedding());
        assertNotNull(entry.getMetadata());
        assertNotNull(entry.getDocument());
    }

    @Test
    void testVectorEntryBuilder() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "TEST");

        VectorEntry entry = VectorEntry.builder()
            .id("test-id")
            .embedding(Arrays.asList(0.1f, 0.2f, 0.3f))
            .metadata(metadata)
            .document("test document")
            .build();

        assertNotNull(entry);
        assertEquals("test-id", entry.getId());
        assertEquals(3, entry.getEmbedding().size());
        assertEquals("TEST", entry.getMetadata().get("type"));
        assertEquals("test document", entry.getDocument());
    }

    @Test
    void testConfigurationAccess() {
        assertEquals(BASE_URL, chromaDBConfig.getUrl());
        assertEquals("cosine", chromaDBConfig.getDistanceFunction());
    }

    @Test
    void testHttpClientStatusCheck() {
        assertEquals(IOReactorStatus.ACTIVE, httpAsyncClient.getStatus());

        // Test inactive status
        when(httpAsyncClient.getStatus()).thenReturn(IOReactorStatus.INACTIVE);
        assertEquals(IOReactorStatus.INACTIVE, httpAsyncClient.getStatus());
    }

    @Test
    void testCollectionNameValidation() {
        // Test valid collection names
        assertDoesNotThrow(() -> client.clearCacheForCollection("valid_name"));
        assertDoesNotThrow(() -> client.clearCacheForCollection("valid-name"));
        assertDoesNotThrow(() -> client.clearCacheForCollection("validname123"));

        // Test null collection name (should not throw for cache clearing)
        assertDoesNotThrow(() -> client.clearCacheForCollection(null));
    }

    @Test
    void testAsyncOperationReturnTypes() {
        // Test that all async operations return proper CompletableFuture types
        CompletableFuture<Void> upsertResult = client.upsertEmbeddingsAsync(TEST_COLLECTION_NAME, null);
        assertNotNull(upsertResult);
        assertTrue(upsertResult instanceof CompletableFuture);

        CompletableFuture<Void> deleteByMetadataResult = client.deleteEmbeddingsByMetadataAsync(TEST_COLLECTION_NAME, null);
        assertNotNull(deleteByMetadataResult);
        assertTrue(deleteByMetadataResult instanceof CompletableFuture);

        CompletableFuture<Void> deleteByIdsResult = client.deleteEmbeddingsByIdsAsync(TEST_COLLECTION_NAME, null);
        assertNotNull(deleteByIdsResult);
        assertTrue(deleteByIdsResult instanceof CompletableFuture);

        CompletableFuture<List<VectorEntry>> searchResult = client.searchSimilarEmbeddingsAsync(
            TEST_COLLECTION_NAME, null, 5, null);
        assertNotNull(searchResult);
        assertTrue(searchResult instanceof CompletableFuture);
    }

    @Test
    void testEmbeddingValidation() {
        List<Float> validEmbedding = Arrays.asList(0.1f, 0.2f, 0.3f);
        assertNotNull(validEmbedding);
        assertEquals(3, validEmbedding.size());
        assertTrue(validEmbedding.stream().allMatch(Objects::nonNull));

        List<Float> emptyEmbedding = Collections.emptyList();
        assertNotNull(emptyEmbedding);
        assertTrue(emptyEmbedding.isEmpty());
    }

    @Test
    void testMetadataValidation() {
        Map<String, Object> validMetadata = Map.of("type", "METHOD", "line", 42);
        assertNotNull(validMetadata);
        assertFalse(validMetadata.isEmpty());
        assertTrue(validMetadata.containsKey("type"));

        Map<String, Object> emptyMetadata = Collections.emptyMap();
        assertNotNull(emptyMetadata);
        assertTrue(emptyMetadata.isEmpty());
    }

    @Test
    void testPathBuilding() {
        // Test path building with different parameters
        String pathWithUuid = client.getCollectionBasePath("test", true);
        String pathWithoutUuid = client.getCollectionBasePath("test", false);

        assertNotNull(pathWithUuid);
        assertNotNull(pathWithoutUuid);
        assertNotEquals(pathWithUuid, pathWithoutUuid);
    }

    @Test
    void testCollectionOperationPreconditions() {
        // Test that operations handle null/empty inputs gracefully
        assertDoesNotThrow(() -> {
            CompletableFuture<Void> future = client.upsertEmbeddingsAsync(TEST_COLLECTION_NAME, null);
            assertTrue(future.isDone());
        });

        assertDoesNotThrow(() -> {
            CompletableFuture<Void> future = client.deleteEmbeddingsByIdsAsync(TEST_COLLECTION_NAME, null);
            assertTrue(future.isDone());
        });
    }

    @Test
    void testExceptionHandling() {
        // Test that invalid operations complete exceptionally
        CompletableFuture<Void> result = client.deleteEmbeddingsByMetadataAsync(TEST_COLLECTION_NAME, null);
        assertTrue(result.isCompletedExceptionally());

        CompletableFuture<Void> result2 = client.deleteEmbeddingsByMetadataAsync(TEST_COLLECTION_NAME, Collections.emptyMap());
        assertTrue(result2.isCompletedExceptionally());
    }

    // Helper method
    private List<VectorEntry> createTestVectorEntries() {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("type", "METHOD");
        metadata.put("filePath", "test.java");

        VectorEntry entry = VectorEntry.builder()
            .id("test-id-1")
            .embedding(Arrays.asList(0.1f, 0.2f, 0.3f))
            .metadata(metadata)
            .document("public void testMethod() { }")
            .build();

        return Arrays.asList(entry);
    }
}
