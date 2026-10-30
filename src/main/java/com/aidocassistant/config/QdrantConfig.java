package com.aidocassistant.config;

import io.qdrant.client.QdrantClient;
import io.qdrant.client.QdrantGrpcClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Spring configuration providing the Qdrant gRPC client bean.
 */
@Configuration
public class QdrantConfig {

    private static final Logger log = LoggerFactory.getLogger(QdrantConfig.class);

    @Bean
    @ConditionalOnMissingBean(QdrantClient.class)
    @ConditionalOnProperty(name = "app.qdrant.enabled", havingValue = "true", matchIfMissing = true)
    public QdrantClient qdrantClient(
            @Value("${spring.ai.vectorstore.qdrant.host:localhost}") String host,
            @Value("${spring.ai.vectorstore.qdrant.port:6334}") int port,
            @Value("${spring.ai.vectorstore.qdrant.api-key:}") String apiKey,
            @Value("${app.qdrant.use-tls:false}") boolean useTls
    ) {
        log.info("Configuring Qdrant gRPC client target: {}:{} (useTLS={})", host, port, useTls);
        QdrantGrpcClient.Builder builder = QdrantGrpcClient.newBuilder(host, port, useTls);
        if (apiKey != null && !apiKey.isBlank()) {
            builder.withApiKey(apiKey);
        }
        return new QdrantClient(builder.build());
    }
}
