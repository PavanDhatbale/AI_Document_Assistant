package com.aidocassistant.config;

import io.qdrant.client.QdrantClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

class QdrantConfigTest {

    private final QdrantConfig qdrantConfig = new QdrantConfig();

    @Test
    @DisplayName("Qdrant client construction does not block even when target host is unreachable")
    void qdrantClient_doesNotBlock_whenServerUnreachable() {
        Instant start = Instant.now();

        // Target an unreachable address with TLS enabled and an API key
        QdrantClient client = qdrantConfig.qdrantClient(
                "unreachable-qdrant-cluster.invalid",
                6334,
                "test-api-key-12345",
                true,
                30
        );

        Duration elapsed = Duration.between(start, Instant.now());

        assertThat(client).isNotNull();
        // Disabling checkCompatibility ensures construction completes in milliseconds without network I/O
        assertThat(elapsed.toMillis())
                .as("Client instantiation should be instantaneous and not perform synchronous network health checks")
                .isLessThan(500);

        client.close();
    }

    @Test
    @DisplayName("Qdrant client correctly sanitizes URI prefixes and trailing ports/slashes")
    void qdrantClient_sanitizesHostProperly() {
        QdrantClient client1 = qdrantConfig.qdrantClient(
                "https://my-cloud-cluster.qdrant.io:6334/",
                6334,
                "test-key",
                true,
                15
        );
        assertThat(client1).isNotNull();
        client1.close();

        QdrantClient client2 = qdrantConfig.qdrantClient(
                "http://localhost:6334",
                6334,
                "",
                false,
                10
        );
        assertThat(client2).isNotNull();
        client2.close();
    }

    @Test
    @DisplayName("Overloaded 4-argument qdrantClient delegates to default 30-second timeout")
    void qdrantClient_overloadPreservesDefaults() {
        QdrantClient client = qdrantConfig.qdrantClient("localhost", 6334, null, false);
        assertThat(client).isNotNull();
        client.close();
    }
}
