package com.aidocassistant.service;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import io.qdrant.client.grpc.Collections;
import io.qdrant.client.grpc.Points;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantCloudLiveTest {

    private Map<String, String> loadEnvFile() {
        Map<String, String> env = new HashMap<>();
        File envFile = new File(".env");
        if (!envFile.exists()) {
            return env;
        }
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(envFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }
                int eq = line.indexOf('=');
                if (eq > 0) {
                    String k = line.substring(0, eq).trim();
                    String v = line.substring(eq + 1).trim();
                    env.put(k, v);
                }
            }
        } catch (Exception ignored) {
        }
        return env;
    }

    @Test
    @DisplayName("Verify live Qdrant Cloud gRPC connectivity, TLS, API key auth, collection and payload indexes")
    void testQdrantCloudConnection() throws Exception {
        Map<String, String> env = loadEnvFile();

        String host = env.getOrDefault("QDRANT_HOST", System.getenv().getOrDefault("QDRANT_HOST", "localhost"));
        int port = Integer.parseInt(env.getOrDefault("QDRANT_PORT", System.getenv().getOrDefault("QDRANT_PORT", "6334")));
        boolean useTls = Boolean.parseBoolean(env.getOrDefault("QDRANT_USE_TLS", System.getenv().getOrDefault("QDRANT_USE_TLS", "true")));
        String apiKey = env.getOrDefault("QDRANT_API_KEY", System.getenv().getOrDefault("QDRANT_API_KEY", ""));
        String collectionName = env.getOrDefault("QDRANT_COLLECTION_NAME", "document_embeddings");

        String cleanHost = host.trim();
        if (cleanHost.startsWith("https://")) {
            cleanHost = cleanHost.substring("https://".length());
        } else if (cleanHost.startsWith("http://")) {
            cleanHost = cleanHost.substring("http://".length());
        }
        if (cleanHost.endsWith("/")) {
            cleanHost = cleanHost.substring(0, cleanHost.length() - 1);
        }
        if (cleanHost.contains(":")) {
            cleanHost = cleanHost.substring(0, cleanHost.indexOf(':'));
        }

        System.out.println("Connecting to Qdrant Cloud target: " + cleanHost + ":" + port + " (useTLS=" + useTls + ")");

        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(cleanHost, port, useTls);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }

        QdrantClient client = new QdrantClient(builder.build());

        try {
            // 1. Test cluster connectivity and authentication
            List<String> collections = client.listCollectionsAsync().get(15, TimeUnit.SECONDS);
            System.out.println("Connected successfully to Qdrant Cloud! Existing collections: " + collections);

            // 2. Verify or create collection 'document_embeddings' with dim=768 and Cosine distance
            boolean exists = client.collectionExistsAsync(collectionName).get(15, TimeUnit.SECONDS);
            if (!exists) {
                System.out.println("Collection '" + collectionName + "' not found. Creating with dim=768, distance=Cosine...");
                Collections.VectorParams vectorParams = Collections.VectorParams.newBuilder()
                        .setSize(768)
                        .setDistance(Collections.Distance.Cosine)
                        .build();

                client.createCollectionAsync(collectionName, vectorParams).get(15, TimeUnit.SECONDS);
                System.out.println("Collection '" + collectionName + "' created successfully!");
            }

            // 3. Ensure payload indexes exist
            try {
                client.createPayloadIndexAsync(
                        Points.CreateFieldIndexCollection.newBuilder()
                                .setCollectionName(collectionName)
                                .setFieldName("user_id")
                                .setFieldType(Points.FieldType.FieldTypeKeyword)
                                .build(),
                        null
                ).get(15, TimeUnit.SECONDS);
                System.out.println("Verified payload index: user_id (keyword)");
            } catch (Exception e) {
                System.out.println("user_id payload index status: " + e.getMessage());
            }

            try {
                client.createPayloadIndexAsync(
                        Points.CreateFieldIndexCollection.newBuilder()
                                .setCollectionName(collectionName)
                                .setFieldName("document_id")
                                .setFieldType(Points.FieldType.FieldTypeKeyword)
                                .build(),
                        null
                ).get(15, TimeUnit.SECONDS);
                System.out.println("Verified payload index: document_id (keyword)");
            } catch (Exception e) {
                System.out.println("document_id payload index status: " + e.getMessage());
            }

            // 4. Verify collection parameters
            Collections.CollectionInfo info = client.getCollectionInfoAsync(collectionName).get(15, TimeUnit.SECONDS);
            assertThat(info).isNotNull();
            assertThat(info.hasConfig()).isTrue();
            assertThat(info.getConfig().getParams().getVectorsConfig().hasParams()).isTrue();

            long size = info.getConfig().getParams().getVectorsConfig().getParams().getSize();
            Collections.Distance distance = info.getConfig().getParams().getVectorsConfig().getParams().getDistance();

            System.out.println("Collection '" + collectionName + "' verified: vectorSize=" + size + ", distance=" + distance);

            assertThat(size).isEqualTo(768L);
            assertThat(distance).isEqualTo(Collections.Distance.Cosine);

        } finally {
            client.close();
        }
    }
}
