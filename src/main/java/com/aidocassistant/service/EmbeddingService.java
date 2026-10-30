package com.aidocassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.google.genai.text.GoogleGenAiTextEmbeddingOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service managing Google Gemini vector embeddings (gemini-embedding-001)
 * with explicit Matryoshka Representation Learning (MRL) dimension enforcement (768),
 * bounded batching, strict float validation (NaN/Infinity guards), and zero-leakage logging.
 */
@Service
public class EmbeddingService {

    private static final Logger log = LoggerFactory.getLogger(EmbeddingService.class);

    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final String modelName;
    private final int embeddingDimension;
    private final int batchSize;

    public EmbeddingService(
            ObjectProvider<EmbeddingModel> embeddingModelProvider,
            @Value("${app.gemini.embedding-model:gemini-embedding-001}") String modelName,
            @Value("${app.gemini.embedding-dimension:768}") int embeddingDimension,
            @Value("${app.gemini.embedding-batch-size:32}") int batchSize
    ) {
        this.embeddingModelProvider = embeddingModelProvider;
        this.modelName = modelName;
        this.embeddingDimension = embeddingDimension;
        this.batchSize = batchSize > 0 ? batchSize : 32;
    }

    /**
     * Generates normalized vector embeddings for a list of document chunk texts.
     * Processes requests in bounded batches and validates vector constraints.
     *
     * @param texts list of text chunks to embed
     * @return list of 768-dimensional float arrays
     */
    public List<float[]> generateEmbeddings(List<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return Collections.emptyList();
        }

        EmbeddingModel model = getEmbeddingModel();
        List<float[]> allVectors = new ArrayList<>(texts.size());

        for (int i = 0; i < texts.size(); i += batchSize) {
            int end = Math.min(i + batchSize, texts.size());
            List<String> batch = texts.subList(i, end);

            List<float[]> batchVectors = callModelForBatch(model, batch, GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_DOCUMENT);
            allVectors.addAll(batchVectors);
        }

        if (allVectors.size() != texts.size()) {
            throw new IllegalStateException("Embedding count mismatch: expected " + texts.size() + ", got " + allVectors.size());
        }

        for (int i = 0; i < allVectors.size(); i++) {
            validateVector(allVectors.get(i), i);
        }

        log.info("Successfully generated embeddings for {} chunks (dimension={})", allVectors.size(), embeddingDimension);
        return allVectors;
    }

    /**
     * Generates a vector embedding for a query string using the RETRIEVAL_QUERY task type.
     *
     * @param queryText search query string
     * @return 768-dimensional float array
     */
    public float[] generateQueryEmbedding(String queryText) {
        if (queryText == null || queryText.isBlank()) {
            throw new IllegalArgumentException("Query text cannot be null or blank");
        }

        EmbeddingModel model = getEmbeddingModel();
        List<float[]> result = callModelForBatch(model, List.of(queryText), GoogleGenAiTextEmbeddingOptions.TaskType.RETRIEVAL_QUERY);
        if (result.isEmpty()) {
            throw new IllegalStateException("Embedding model returned empty result for query");
        }

        float[] vector = result.get(0);
        validateVector(vector, 0);
        return vector;
    }

    private List<float[]> callModelForBatch(EmbeddingModel model, List<String> batch, GoogleGenAiTextEmbeddingOptions.TaskType taskType) {
        GoogleGenAiTextEmbeddingOptions options = GoogleGenAiTextEmbeddingOptions.builder()
                .model(modelName)
                .dimensions(embeddingDimension)
                .taskType(taskType)
                .build();

        try {
            EmbeddingRequest request = new EmbeddingRequest(batch, options);
            EmbeddingResponse response = model.call(request);

            if (response != null && response.getResults() != null && !response.getResults().isEmpty()) {
                return response.getResults().stream()
                        .map(Embedding::getOutput)
                        .toList();
            }
        } catch (UnsupportedOperationException e) {
            log.debug("EmbeddingModel does not support EmbeddingRequest with options; falling back to embed(List<String>)");
        }

        // Fallback for mocks or basic models only implementing embed(List<String>)
        List<float[]> fallback = model.embed(batch);
        if (fallback == null || fallback.isEmpty()) {
            throw new IllegalStateException("Embedding model returned null or empty response");
        }
        return fallback;
    }

    /**
     * Strictly validates vector dimensions, nullity, and float finiteness (rejecting NaN and Infinities).
     */
    public void validateVector(float[] vector, int index) {
        if (vector == null) {
            throw new IllegalStateException("Embedding vector at index " + index + " is null");
        }
        if (vector.length == 0) {
            throw new IllegalStateException("Embedding vector at index " + index + " is empty");
        }
        if (vector.length != embeddingDimension) {
            throw new IllegalStateException("Embedding vector at index " + index + " has invalid dimension "
                    + vector.length + ", expected " + embeddingDimension);
        }
        for (int d = 0; d < vector.length; d++) {
            float val = vector[d];
            if (!Float.isFinite(val)) {
                throw new IllegalStateException("Embedding vector at index " + index + " contains non-finite value at dim " + d + ": " + val);
            }
        }
    }

    private EmbeddingModel getEmbeddingModel() {
        EmbeddingModel model = embeddingModelProvider.getIfAvailable();
        if (model == null) {
            throw new IllegalStateException("EmbeddingModel bean is not configured in application context");
        }
        return model;
    }

    public int getEmbeddingDimension() {
        return embeddingDimension;
    }

    public String getModelName() {
        return modelName;
    }

    public int getBatchSize() {
        return batchSize;
    }
}
