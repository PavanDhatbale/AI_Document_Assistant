package com.aidocassistant.service;

import com.aidocassistant.dto.RetrievedChunk;
import com.aidocassistant.model.DocumentChunk;
import com.google.common.util.concurrent.ListenableFuture;
import io.qdrant.client.ConditionFactory;
import io.qdrant.client.PointIdFactory;
import io.qdrant.client.QdrantClient;
import io.qdrant.client.ValueFactory;
import io.qdrant.client.VectorsFactory;
import io.qdrant.client.WithPayloadSelectorFactory;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.JsonWithInt;
import io.qdrant.client.grpc.Points;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Service managing Qdrant vector collection initialization, schema verification,
 * deterministic chunk indexing, multi-tenant scoped retrieval, and vector lifecycle deletion.
 */
@Service
public class VectorStoreService {

    private static final Logger log = LoggerFactory.getLogger(VectorStoreService.class);

    private final ObjectProvider<QdrantClient> qdrantClientProvider;
    private final String collectionName;
    private final int vectorDimension;
    private final int operationTimeoutSeconds;
    private final int upsertBatchSize;
    private final boolean qdrantEnabled;
    private volatile boolean collectionInitialized = false;

    @Autowired
    public VectorStoreService(
            ObjectProvider<QdrantClient> qdrantClientProvider,
            @Value("${spring.ai.vectorstore.qdrant.collection-name:document_embeddings}") String collectionName,
            @Value("${app.gemini.embedding-dimension:768}") int vectorDimension,
            @Value("${app.qdrant.operation-timeout-seconds:30}") int operationTimeoutSeconds,
            @Value("${app.qdrant.upsert-batch-size:64}") int upsertBatchSize,
            @Value("${app.qdrant.enabled:true}") boolean qdrantEnabled
    ) {
        this.qdrantClientProvider = qdrantClientProvider;
        this.collectionName = collectionName;
        this.vectorDimension = vectorDimension;
        this.operationTimeoutSeconds = operationTimeoutSeconds > 0 ? operationTimeoutSeconds : 30;
        this.upsertBatchSize = upsertBatchSize > 0 ? upsertBatchSize : 64;
        this.qdrantEnabled = qdrantEnabled;
    }

    public VectorStoreService(
            ObjectProvider<QdrantClient> qdrantClientProvider,
            String collectionName,
            int vectorDimension,
            int operationTimeoutSeconds,
            int upsertBatchSize
    ) {
        this(qdrantClientProvider, collectionName, vectorDimension, operationTimeoutSeconds, upsertBatchSize, true);
    }

    /**
     * Startup lifecycle hook: ensures the Qdrant vector collection and payload indexes
     * are initialized on application startup when Qdrant is enabled and reachable.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void onApplicationReady() {
        if (!qdrantEnabled) {
            log.debug("Qdrant integration is disabled in configuration; skipping startup collection initialization");
            return;
        }
        QdrantClient client = qdrantClientProvider.getIfAvailable();
        if (client == null) {
            log.debug("QdrantClient bean is not available; skipping startup collection initialization");
            return;
        }
        try {
            log.info("Checking and initializing Qdrant collection '{}' on application startup...", collectionName);
            initCollection();
        } catch (Exception e) {
            log.warn("Qdrant collection initialization deferred on startup: {}. It will be initialized before first vector operation.", e.getMessage());
        }
    }

    public synchronized void resetCollectionInitialized() {
        this.collectionInitialized = false;
    }

    /**
     * Ensures the collection exists before performing vector operations.
     * Initializes if not yet initialized or if the collection was removed.
     */
    public synchronized void ensureCollectionExists() {
        if (qdrantClientProvider.getIfAvailable() == null) {
            return;
        }
        if (!collectionInitialized) {
            initCollection();
            return;
        }
        try {
            ListenableFuture<Boolean> existsFuture = getClient().collectionExistsAsync(collectionName);
            if (existsFuture != null && !existsFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS)) {
                log.info("Qdrant collection '{}' missing; re-initializing schema...", collectionName);
                initCollection();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted checking Qdrant collection: " + e.getMessage(), e);
        } catch (Exception e) {
            log.debug("Pre-flight collection existence check skipped: {}", e.getMessage());
        }
    }

    /**
     * Initializes or verifies the Qdrant vector collection.
     * If missing, creates the collection with 768-dim vectors, Cosine distance,
     * and payload indexes on user_id and document_id.
     * If existing, asserts dimension and distance match, failing fast if mismatched.
     * Safe to invoke repeatedly; thread-safe and idempotent.
     */
    public synchronized void initCollection() {
        QdrantClient client = getClient();
        try {
            ListenableFuture<Boolean> existsFuture = client.collectionExistsAsync(collectionName);
            if (existsFuture == null) {
                log.debug("collectionExistsAsync returned null (mock client); skipping initialization");
                this.collectionInitialized = true;
                return;
            }

            boolean exists = existsFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
            if (!exists) {
                log.info("Qdrant collection '{}' does not exist. Creating with dim={}, distance=Cosine",
                        collectionName, vectorDimension);

                Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                        .setSize(vectorDimension)
                        .setDistance(Collections.Distance.Cosine)
                        .build();

                ListenableFuture<Collections.CollectionOperationResponse> createFuture =
                        client.createCollectionAsync(collectionName, vectorParams);
                if (createFuture != null) {
                    createFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
                }

                // Create payload keyword index for multi-tenant isolation
                ListenableFuture<Points.UpdateResult> userIndexFuture = client.createPayloadIndexAsync(
                        Points.CreateFieldIndexCollection.newBuilder()
                                .setCollectionName(collectionName)
                                .setFieldName("user_id")
                                .setFieldType(Points.FieldType.FieldTypeKeyword)
                                .build(),
                        null
                );
                if (userIndexFuture != null) {
                    userIndexFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
                }

                // Create payload keyword index for document filtering
                ListenableFuture<Points.UpdateResult> docIndexFuture = client.createPayloadIndexAsync(
                        Points.CreateFieldIndexCollection.newBuilder()
                                .setCollectionName(collectionName)
                                .setFieldName("document_id")
                                .setFieldType(Points.FieldType.FieldTypeKeyword)
                                .build(),
                        null
                );
                if (docIndexFuture != null) {
                    docIndexFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
                }

                log.info("Successfully created Qdrant collection '{}' with payload indexes", collectionName);
            } else {
                ListenableFuture<Collections.CollectionInfo> infoFuture = client.getCollectionInfoAsync(collectionName);
                if (infoFuture != null) {
                    Collections.CollectionInfo info = infoFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
                    verifyCollectionConfig(info);
                }
                log.info("Qdrant collection '{}' verified successfully (dim={}, distance=Cosine)",
                        collectionName, vectorDimension);
            }
            this.collectionInitialized = true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while configuring Qdrant collection: " + e.getMessage(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Failed to initialize or verify Qdrant collection: " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Timed out while configuring Qdrant collection '" + collectionName + "': " + e.getMessage(), e);
        }
    }

    private void verifyCollectionConfig(Collections.CollectionInfo info) {
        if (info != null && info.hasConfig() && info.getConfig().hasParams() && info.getConfig().getParams().hasVectorsConfig()) {
            Collections.VectorsConfig vectorsConfig = info.getConfig().getParams().getVectorsConfig();
            if (vectorsConfig.hasParams()) {
                long existingSize = vectorsConfig.getParams().getSize();
                Collections.Distance existingDistance = vectorsConfig.getParams().getDistance();

                if (existingSize != vectorDimension || existingDistance != Collections.Distance.Cosine) {
                    throw new IllegalStateException(String.format(
                            "Qdrant collection '%s' configuration mismatch: expected dimension %d with Cosine distance, but found dimension %d with distance %s",
                            collectionName, vectorDimension, existingSize, existingDistance
                    ));
                }
            }
        }
    }

    /**
     * Deterministically indexes document chunks and corresponding vectors in Qdrant.
     * Point IDs are generated using UUIDv3 (nameUUIDFromBytes) based on documentId and chunkIndex,
     * ensuring idempotent upserts and seamless overwrite on reprocessing.
     *
     * @param userId owner of the document
     * @param documentId document identifier
     * @param chunks persisted document chunks
     * @param embeddings vector embeddings matching chunk list
     */
    public void indexChunks(UUID userId, UUID documentId, List<DocumentChunk> chunks, List<float[]> embeddings) {
        if (chunks == null || chunks.isEmpty()) {
            return;
        }
        if (chunks.size() != embeddings.size()) {
            throw new IllegalArgumentException(String.format(
                    "Chunks count (%d) does not match embeddings count (%d)", chunks.size(), embeddings.size()));
        }

        // Guarantee collection and payload indexes exist prior to any vector upsert
        ensureCollectionExists();

        QdrantClient client = getClient();
        List<Points.PointStruct> points = new ArrayList<>(chunks.size());

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunk chunk = chunks.get(i);
            float[] vector = embeddings.get(i);

            // Deterministic Point ID: UUIDv3(documentId + "_" + chunkIndex)
            UUID pointUuid = generatePointId(documentId, chunk.getChunkIndex());

            Map<String, JsonWithInt.Value> payload = new HashMap<>();
            payload.put("user_id", ValueFactory.value(userId.toString()));
            payload.put("document_id", ValueFactory.value(documentId.toString()));
            payload.put("chunk_id", ValueFactory.value(chunk.getId().toString()));
            payload.put("chunk_index", ValueFactory.value((long) chunk.getChunkIndex()));
            payload.put("page_number", ValueFactory.value((long) chunk.getPageNumber()));
            payload.put("chunk_text", ValueFactory.value(chunk.getChunkText()));

            Points.PointStruct point = Points.PointStruct.newBuilder()
                    .setId(PointIdFactory.id(pointUuid))
                    .setVectors(VectorsFactory.vectors(vector))
                    .putAllPayload(payload)
                    .build();

            points.add(point);
        }

        for (int b = 0; b < points.size(); b += upsertBatchSize) {
            int end = Math.min(b + upsertBatchSize, points.size());
            List<Points.PointStruct> batch = points.subList(b, end);
            try {
                Points.UpdateResult updateResult = client.upsertAsync(collectionName, batch)
                        .get(operationTimeoutSeconds, TimeUnit.SECONDS);

                if (updateResult.getStatus() != Points.UpdateStatus.Completed
                        && updateResult.getStatus() != Points.UpdateStatus.Acknowledged) {
                    throw new IllegalStateException("Qdrant point upsert failed with status: " + updateResult.getStatus());
                }
            } catch (Exception e) {
                Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
                String cleanupNote;
                try {
                    deleteDocumentVectors(userId, documentId);
                    cleanupNote = "Partial vectors purged";
                } catch (Exception cleanupEx) {
                    log.error("Failed to clean up partial vectors after upsert failure for doc {}: {}", documentId, cleanupEx.getMessage());
                    cleanupNote = "Cleanup failed: " + cleanupEx.getMessage();
                }

                if (e instanceof InterruptedException) {
                    Thread.currentThread().interrupt();
                }

                throw new IllegalStateException(String.format(
                        "Qdrant point upsert failed at batch [%d-%d] of %d: %s. [%s]",
                        b, end, points.size(), cause.getMessage(), cleanupNote), cause);
            }
        }
        log.info("Successfully indexed {} vector points in Qdrant for document {}", points.size(), documentId);
    }

    /**
     * Purges all vectors belonging to a specific document under the authenticated user.
     * Enforces user_id in the deletion filter to ensure cross-user isolation.
     * Safe if collection does not exist (skips cleanly without throwing exceptions).
     *
     * @param userId owner of the document
     * @param documentId document identifier
     */
    public void deleteDocumentVectors(UUID userId, UUID documentId) {
        QdrantClient client = qdrantClientProvider.getIfAvailable();
        if (client == null) {
            log.warn("QdrantClient bean not available; skipping vector deletion for document {}", documentId);
            return;
        }

        // Verify collection existence first to avoid failing during cleanup if collection is absent
        ListenableFuture<Boolean> existsFuture = client.collectionExistsAsync(collectionName);
        if (existsFuture != null) {
            try {
                boolean exists = existsFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
                if (!exists) {
                    log.debug("Qdrant collection '{}' does not exist; skipping vector deletion for document {}", collectionName, documentId);
                    return;
                }
            } catch (Exception e) {
                log.debug("Could not verify collection existence prior to vector deletion: {}", e.getMessage());
            }
        }

        Points.Filter filter = Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("user_id", userId.toString()))
                .addMust(ConditionFactory.matchKeyword("document_id", documentId.toString()))
                .build();

        try {
            Points.UpdateResult updateResult = client.deleteAsync(collectionName, filter)
                    .get(operationTimeoutSeconds, TimeUnit.SECONDS);
            if (updateResult.getStatus() != Points.UpdateStatus.Completed
                    && updateResult.getStatus() != Points.UpdateStatus.Acknowledged) {
                throw new IllegalStateException("Qdrant vector deletion failed with status: " + updateResult.getStatus());
            }
            log.info("Successfully deleted vector points from Qdrant for document {} and user {}", documentId, userId);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while deleting vectors from Qdrant: " + e.getMessage(), e);
        } catch (Exception e) {
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
            throw new IllegalStateException("Failed to delete vectors from Qdrant: " + cause.getMessage(), cause);
        }
    }

    /**
     * Searches for document chunks semantically similar to a query vector.
     * Enforces strict multi-tenant isolation via mandatory user_id filter.
     * Optionally scopes retrieval to a subset of owned document IDs.
     * Safe if collection does not exist (returns empty list).
     *
     * @param userId authenticated user ID
     * @param documentIds optional document filter list (empty or null searches all user's documents)
     * @param queryVector 768-dim query embedding
     * @param topK maximum candidate chunks to return
     * @param similarityThreshold minimum Cosine similarity score
     * @return list of retrieved chunks meeting criteria
     */
    public List<RetrievedChunk> searchSimilar(
            UUID userId,
            List<UUID> documentIds,
            float[] queryVector,
            int topK,
            double similarityThreshold
    ) {
        if (queryVector == null || queryVector.length != vectorDimension) {
            throw new IllegalArgumentException("Invalid query vector dimension: expected " + vectorDimension);
        }

        QdrantClient client = getClient();

        // If collection does not exist yet, no chunks have been indexed
        ListenableFuture<Boolean> existsFuture = client.collectionExistsAsync(collectionName);
        if (existsFuture != null) {
            try {
                boolean exists = existsFuture.get(operationTimeoutSeconds, TimeUnit.SECONDS);
                if (!exists) {
                    log.debug("Qdrant collection '{}' does not exist; returning empty search results", collectionName);
                    return List.of();
                }
            } catch (Exception e) {
                log.debug("Could not verify collection existence prior to search: {}", e.getMessage());
            }
        }

        Points.Filter.Builder filterBuilder = Points.Filter.newBuilder()
                .addMust(ConditionFactory.matchKeyword("user_id", userId.toString()));

        if (documentIds != null && !documentIds.isEmpty()) {
            if (documentIds.size() == 1) {
                filterBuilder.addMust(ConditionFactory.matchKeyword("document_id", documentIds.get(0).toString()));
            } else {
                List<String> docIdStrings = documentIds.stream().map(UUID::toString).toList();
                filterBuilder.addMust(ConditionFactory.matchKeywords("document_id", docIdStrings));
            }
        }

        Points.Filter filter = filterBuilder.build();

        List<Float> floatList = new ArrayList<>(queryVector.length);
        for (float v : queryVector) {
            floatList.add(v);
        }

        Points.SearchPoints searchPoints = Points.SearchPoints.newBuilder()
                .setCollectionName(collectionName)
                .addAllVector(floatList)
                .setFilter(filter)
                .setLimit(topK)
                .setWithPayload(WithPayloadSelectorFactory.enable(true))
                .setScoreThreshold((float) similarityThreshold)
                .build();

        try {
            List<Points.ScoredPoint> scoredPoints = client.searchAsync(searchPoints)
                    .get(operationTimeoutSeconds, TimeUnit.SECONDS);
            if (scoredPoints == null || scoredPoints.isEmpty()) {
                return List.of();
            }

            return scoredPoints.stream()
                    .map(this::mapToRetrievedChunk)
                    .toList();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while executing Qdrant search: " + e.getMessage(), e);
        } catch (Exception e) {
            Throwable cause = (e instanceof ExecutionException && e.getCause() != null) ? e.getCause() : e;
            throw new IllegalStateException("Failed to execute Qdrant search: " + cause.getMessage(), cause);
        }
    }

    private RetrievedChunk mapToRetrievedChunk(Points.ScoredPoint sp) {
        Map<String, JsonWithInt.Value> payload = sp.getPayloadMap();

        UUID docId = UUID.fromString(payload.get("document_id").getStringValue());
        UUID chunkId = UUID.fromString(payload.get("chunk_id").getStringValue());
        int chunkIndex = (int) payload.get("chunk_index").getIntegerValue();
        int pageNumber = (int) payload.get("page_number").getIntegerValue();
        String text = payload.get("chunk_text").getStringValue();
        double score = sp.getScore();

        return new RetrievedChunk(docId, chunkId, chunkIndex, pageNumber, text, score);
    }

    /**
     * Generates a deterministic UUID based on documentId and chunkIndex.
     */
    public UUID generatePointId(UUID documentId, int chunkIndex) {
        String key = documentId.toString() + "_" + chunkIndex;
        return UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }

    private QdrantClient getClient() {
        QdrantClient client = qdrantClientProvider.getIfAvailable();
        if (client == null) {
            throw new IllegalStateException("QdrantClient bean is not configured in application context");
        }
        return client;
    }

    public String getCollectionName() {
        return collectionName;
    }

    public int getVectorDimension() {
        return vectorDimension;
    }
}
