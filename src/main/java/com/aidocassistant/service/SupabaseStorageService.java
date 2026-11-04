package com.aidocassistant.service;

import com.aidocassistant.exception.FileStorageException;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Cloud storage service implementation backed by Supabase Storage REST API.
 * Uploads, downloads, and deletes documents in a private Supabase bucket.
 * Organizes files by user identifier: {userId}/{fileUuid}.pdf
 */
public class SupabaseStorageService extends AbstractStorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);

    private final String supabaseUrl;
    private final String supabaseKey;
    private final String bucketName;
    private final String baseStorageUrl;
    private final RestTemplate restTemplate;
    private final LocalStorageService localStorageService;

    public SupabaseStorageService(
            String supabaseUrl,
            String supabaseKey,
            String bucketName,
            long maxFileSizeBytes,
            int timeoutSeconds,
            RestTemplateBuilder restTemplateBuilder
    ) {
        this(supabaseUrl, supabaseKey, bucketName, maxFileSizeBytes, timeoutSeconds, restTemplateBuilder, null);
    }

    public SupabaseStorageService(
            String supabaseUrl,
            String supabaseKey,
            String bucketName,
            long maxFileSizeBytes,
            int timeoutSeconds,
            RestTemplateBuilder restTemplateBuilder,
            LocalStorageService localStorageService
    ) {
        this(supabaseUrl, supabaseKey, bucketName, maxFileSizeBytes,
                restTemplateBuilder
                        .setConnectTimeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 30))
                        .setReadTimeout(Duration.ofSeconds(timeoutSeconds > 0 ? timeoutSeconds : 30))
                        .build(),
                localStorageService);
    }

    public SupabaseStorageService(
            String supabaseUrl,
            String supabaseKey,
            String bucketName,
            long maxFileSizeBytes,
            RestTemplate restTemplate
    ) {
        this(supabaseUrl, supabaseKey, bucketName, maxFileSizeBytes, restTemplate, null);
    }

    public SupabaseStorageService(
            String supabaseUrl,
            String supabaseKey,
            String bucketName,
            long maxFileSizeBytes,
            RestTemplate restTemplate,
            LocalStorageService localStorageService
    ) {
        super(maxFileSizeBytes);
        this.supabaseUrl = sanitizeUrl(supabaseUrl);
        this.supabaseKey = supabaseKey != null ? supabaseKey.trim() : "";
        this.bucketName = bucketName != null && !bucketName.isBlank() ? bucketName.trim() : "ai-document-assistant-pdfs";
        this.baseStorageUrl = this.supabaseUrl + "/storage/v1";
        this.restTemplate = restTemplate;
        this.localStorageService = localStorageService;
    }

    @PostConstruct
    public void validateConfiguration() {
        if (supabaseUrl == null || supabaseUrl.isBlank()) {
            throw new IllegalStateException("SUPABASE_URL must be configured when app.storage.type is set to 'supabase'");
        }
        if (supabaseKey == null || supabaseKey.isBlank()) {
            throw new IllegalStateException("SUPABASE_KEY must be configured when app.storage.type is set to 'supabase'");
        }
        log.info("Initialized Supabase Storage provider targeting bucket '{}' at: {}", bucketName, supabaseUrl);
    }

    /**
     * Determines whether a storage key belongs to legacy local filesystem storage or Supabase Cloud Storage.
     * Supabase objects are always namespaced by user directory ({userId}/{fileUuid}.pdf) containing forward slashes.
     * Legacy local files are stored as flat filenames ({fileUuid}.pdf) with no folder hierarchy.
     */
    public boolean isLegacyLocalKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        // If the key has no folder delimiters, it is definitely a flat local filename
        if (!storageKey.contains("/") && !storageKey.contains("\\")) {
            return true;
        }
        // If local storage service exists and has the file on disk
        if (localStorageService != null && localStorageService.exists(storageKey)) {
            return true;
        }
        return false;
    }

    public LocalStorageService getLocalStorageService() {
        return localStorageService;
    }

    @Override
    public String storeFile(UUID userId, byte[] fileBytes) {
        validateFileBytes(fileBytes);
        String prefix = (userId != null) ? userId.toString() : "unassigned";
        String storageKey = prefix + "/" + UUID.randomUUID() + ".pdf";
        return uploadToSupabase(storageKey, fileBytes);
    }

    /**
     * Uploads bytes to Supabase Storage at the specified storage key.
     */
    public String uploadToSupabase(String storageKey, byte[] fileBytes) {
        validateStorageKey(storageKey);
        validateFileBytes(fileBytes);

        URI uploadUri = buildObjectUri("standard", storageKey);

        HttpHeaders headers = createAuthHeaders(MediaType.APPLICATION_PDF);
        headers.set("x-upsert", "true");
        HttpEntity<byte[]> requestEntity = new HttpEntity<>(fileBytes, headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    uploadUri,
                    HttpMethod.POST,
                    requestEntity,
                    String.class
            );

            if (!response.getStatusCode().is2xxSuccessful()) {
                throw new FileStorageException("Supabase Storage returned unexpected HTTP status: " + response.getStatusCode().value());
            }

            log.info("Successfully uploaded object to Supabase bucket '{}': key={}", bucketName, sanitizeKey(storageKey));
            return storageKey;
        } catch (HttpStatusCodeException e) {
            log.error("Supabase Storage upload failed: status={}, bucket={}, key={}",
                    e.getStatusCode().value(), bucketName, sanitizeKey(storageKey));
            throw new FileStorageException("Failed to upload document to Supabase storage (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.error("Supabase Storage upload network/connection error: bucket={}, key={}, error={}",
                    bucketName, sanitizeKey(storageKey), e.getMessage());
            throw new FileStorageException("Could not connect to Supabase storage: " + e.getMessage(), e);
        }
    }

    @Override
    public byte[] loadFile(String storageKey) {
        validateStorageKey(storageKey);

        if (isLegacyLocalKey(storageKey)) {
            if (localStorageService != null && localStorageService.exists(storageKey)) {
                log.info("Storage provider routed legacy local file '{}' load to LocalStorageService", sanitizeKey(storageKey));
                return localStorageService.loadFile(storageKey);
            }
        }

        // Primary private bucket endpoint: /storage/v1/object/authenticated/{bucket}/{wildcard}
        URI downloadUri = buildObjectUri("authenticated", storageKey);

        HttpHeaders headers = createAuthHeaders(null);
        headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_PDF, MediaType.ALL));
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            ResponseEntity<byte[]> response = restTemplate.exchange(
                    downloadUri,
                    HttpMethod.GET,
                    requestEntity,
                    byte[].class
            );

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                throw new FileStorageException("Stored document file not found or empty in Supabase storage");
            }

            return response.getBody();
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                // Secondary fallback attempt: /storage/v1/object/{bucket}/{wildcard}
                byte[] fallback = tryFallbackDownload(storageKey);
                if (fallback != null) {
                    return fallback;
                }
                boolean isLegacyKey = !storageKey.contains("/");
                if (isLegacyKey) {
                    throw new FileStorageException(String.format(
                            "Document file '%s' was not found in Supabase storage. This document was originally stored on the local filesystem and must be migrated or re-uploaded for cloud storage.",
                            sanitizeKey(storageKey)
                    ), e);
                }
                throw new FileStorageException("Stored document file not found in Supabase storage: " + sanitizeKey(storageKey), e);
            }
            log.error("Supabase Storage download failed: status={}, bucket={}, key={}",
                    e.getStatusCode().value(), bucketName, sanitizeKey(storageKey));
            throw new FileStorageException("Failed to download document from Supabase storage (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.error("Supabase Storage download network error: bucket={}, key={}, error={}",
                    bucketName, sanitizeKey(storageKey), e.getMessage());
            throw new FileStorageException("Could not connect to Supabase storage: " + e.getMessage(), e);
        }
    }

    @Override
    public void deleteFile(String storageKey) {
        validateStorageKey(storageKey);

        if (isLegacyLocalKey(storageKey)) {
            if (localStorageService != null) {
                log.info("Storage provider routed legacy local file '{}' deletion to LocalStorageService", sanitizeKey(storageKey));
                localStorageService.deleteFile(storageKey);
                return;
            } else {
                log.warn("Legacy local file '{}' identified for deletion, but LocalStorageService is unconfigured; skipping local deletion",
                        sanitizeKey(storageKey));
                return;
            }
        }

        URI deleteUri = buildObjectUri("standard", storageKey);

        HttpHeaders headers = createAuthHeaders(null);
        HttpEntity<Void> requestEntity = new HttpEntity<>(headers);

        try {
            ResponseEntity<String> response = restTemplate.exchange(
                    deleteUri,
                    HttpMethod.DELETE,
                    requestEntity,
                    String.class
            );

            if (response.getStatusCode().is2xxSuccessful()) {
                log.info("Successfully deleted object from Supabase bucket '{}': key={}", bucketName, sanitizeKey(storageKey));
            }
        } catch (HttpStatusCodeException e) {
            if (e.getStatusCode() == HttpStatus.NOT_FOUND) {
                log.warn("Object '{}' not found in Supabase bucket '{}' during deletion; already removed",
                        sanitizeKey(storageKey), bucketName);
                return;
            }
            log.error("Supabase Storage deletion failed: status={}, bucket={}, key={}",
                    e.getStatusCode().value(), bucketName, sanitizeKey(storageKey));
            throw new FileStorageException("Failed to delete document from Supabase storage (HTTP " + e.getStatusCode().value() + ")", e);
        } catch (RestClientException e) {
            log.error("Supabase Storage deletion network error: bucket={}, key={}, error={}",
                    bucketName, sanitizeKey(storageKey), e.getMessage());
            throw new FileStorageException("Could not connect to Supabase storage: " + e.getMessage(), e);
        }
    }

    @Override
    public void cleanupFileQuietly(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return;
        }
        if (isLegacyLocalKey(storageKey)) {
            if (localStorageService != null) {
                localStorageService.cleanupFileQuietly(storageKey);
            }
            return;
        }
        try {
            deleteFile(storageKey);
        } catch (Exception e) {
            log.warn("Quiet cleanup failed for Supabase object '{}': {}", sanitizeKey(storageKey), e.getMessage());
        }
    }

    public boolean exists(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            return false;
        }
        if (isLegacyLocalKey(storageKey)) {
            return localStorageService != null && localStorageService.exists(storageKey);
        }
        try {
            URI infoUri = buildObjectUri("authenticated", storageKey);
            HttpHeaders headers = createAuthHeaders(null);
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<byte[]> response = restTemplate.exchange(infoUri, HttpMethod.HEAD, entity, byte[].class);
            return response.getStatusCode().is2xxSuccessful();
        } catch (HttpStatusCodeException e) {
            return false;
        } catch (Exception e) {
            log.warn("Failed to check Supabase object existence for '{}': {}", sanitizeKey(storageKey), e.getMessage());
            return false;
        }
    }

    private byte[] tryFallbackDownload(String storageKey) {
        try {
            URI fallbackUri = buildObjectUri("standard", storageKey);
            HttpHeaders headers = createAuthHeaders(null);
            headers.setAccept(List.of(MediaType.APPLICATION_OCTET_STREAM, MediaType.APPLICATION_PDF, MediaType.ALL));
            HttpEntity<Void> entity = new HttpEntity<>(headers);

            ResponseEntity<byte[]> response = restTemplate.exchange(
                    fallbackUri,
                    HttpMethod.GET,
                    entity,
                    byte[].class
            );
            if (response.getStatusCode().is2xxSuccessful() && response.getBody() != null) {
                return response.getBody();
            }
        } catch (Exception ignored) {
            // Ignored; caller will handle 404
        }
        return null;
    }

    /**
     * Builds a properly encoded URI for Supabase Storage REST endpoints using UriComponentsBuilder.
     * Encodes path segments (safely handling spaces and reserved characters) while preserving
     * path slashes in hierarchical keys (e.g. {userId}/{uuid}.pdf) and avoiding double-encoding.
     *
     * @param endpointType "authenticated" for private download, or "standard" for upload/delete/public
     * @param storageKey   the relative storage key (e.g. "userId/uuid.pdf")
     * @return safely constructed and encoded URI
     */
    public URI buildObjectUri(String endpointType, String storageKey) {
        validateStorageKey(storageKey);

        UriComponentsBuilder builder = UriComponentsBuilder.fromHttpUrl(baseStorageUrl);
        if ("authenticated".equalsIgnoreCase(endpointType)) {
            builder.pathSegment("object", "authenticated", bucketName);
        } else {
            builder.pathSegment("object", bucketName);
        }

        // Split storageKey by forward slash to preserve folder structure without encoding slashes as %2F
        String[] segments = storageKey.split("/");
        for (String segment : segments) {
            if (!segment.isEmpty()) {
                builder.pathSegment(segment);
            }
        }

        return builder.build().encode().toUri();
    }

    private HttpHeaders createAuthHeaders(MediaType contentType) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("apikey", supabaseKey);
        headers.setBearerAuth(supabaseKey);
        if (contentType != null) {
            headers.setContentType(contentType);
        }
        return headers;
    }

    private void validateStorageKey(String storageKey) {
        if (storageKey == null || storageKey.isBlank()) {
            throw new SecurityException("Storage key cannot be empty");
        }
        if (storageKey.contains("..")) {
            throw new SecurityException("Storage path traversal attempted");
        }
        if (storageKey.startsWith("/") || storageKey.startsWith("\\")) {
            throw new SecurityException("Storage key cannot start with leading slash");
        }
    }

    private void validateFileBytes(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new IllegalArgumentException("File bytes cannot be empty or null");
        }
    }

    private String sanitizeUrl(String url) {
        if (url == null) return "";
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        if (trimmed.endsWith("/rest/v1")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/rest/v1".length());
        } else if (trimmed.endsWith("/storage/v1")) {
            trimmed = trimmed.substring(0, trimmed.length() - "/storage/v1".length());
        }
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private String sanitizeKey(String key) {
        return key != null ? key.replaceAll("[\r\n]", "") : "";
    }

    public String getBucketName() {
        return bucketName;
    }
}
