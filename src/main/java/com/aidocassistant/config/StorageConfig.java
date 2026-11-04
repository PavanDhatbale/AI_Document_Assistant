package com.aidocassistant.config;

import com.aidocassistant.service.FileStorageService;
import com.aidocassistant.service.LocalStorageService;
import com.aidocassistant.service.StorageService;
import com.aidocassistant.service.SupabaseStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import java.time.Duration;

/**
 * Configuration selecting the StorageService provider (local filesystem vs Supabase Storage).
 */
@Configuration
public class StorageConfig {

    private static final Logger log = LoggerFactory.getLogger(StorageConfig.class);

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "supabase")
    public StorageService supabaseStorageService(
            @Value("${app.storage.supabase.url:}") String supabaseUrl,
            @Value("${app.storage.supabase.key:}") String supabaseKey,
            @Value("${app.storage.supabase.bucket-name:ai-document-assistant-pdfs}") String bucketName,
            @Value("${app.storage.upload-dir:./storage/uploads}") String uploadDir,
            @Value("${app.storage.max-file-size-bytes:26214400}") long maxFileSizeBytes,
            @Value("${app.storage.supabase.timeout-seconds:30}") int timeoutSeconds,
            RestTemplateBuilder restTemplateBuilder
    ) {
        log.info("Registering Supabase Storage provider for bucket '{}'", bucketName);
        LocalStorageService localFallback = new LocalStorageService(uploadDir, maxFileSizeBytes);
        localFallback.init();
        return new SupabaseStorageService(
                supabaseUrl,
                supabaseKey,
                bucketName,
                maxFileSizeBytes,
                timeoutSeconds,
                restTemplateBuilder,
                localFallback
        );
    }

    @Bean
    @Primary
    @ConditionalOnProperty(name = "app.storage.type", havingValue = "local", matchIfMissing = true)
    public FileStorageService localStorageService(
            @Value("${app.storage.upload-dir:./storage/uploads}") String uploadDir,
            @Value("${app.storage.max-file-size-bytes:26214400}") long maxFileSizeBytes
    ) {
        log.info("Registering Local Filesystem Storage provider at '{}'", uploadDir);
        return new FileStorageService(uploadDir, maxFileSizeBytes);
    }
}
