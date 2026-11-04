package com.aidocassistant.service;

import com.aidocassistant.exception.FileStorageException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestTemplate;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class SupabaseStorageServiceTest {

    private static final String SUPABASE_URL = "https://mockproject.supabase.co";
    private static final String SUPABASE_KEY = "mock-service-role-secret-key-12345";
    private static final String BUCKET_NAME = "test-doc-bucket";

    private RestTemplate restTemplate;
    private MockRestServiceServer mockServer;
    private SupabaseStorageService storageService;

    @BeforeEach
    void setUp() {
        restTemplate = new RestTemplate();
        mockServer = MockRestServiceServer.createServer(restTemplate);
        storageService = new SupabaseStorageService(
                SUPABASE_URL,
                SUPABASE_KEY,
                BUCKET_NAME,
                25 * 1024 * 1024,
                restTemplate
        );
        storageService.validateConfiguration();
    }

    @Test
    @DisplayName("buildObjectUri safely encodes spaces and reserved characters while preserving slashes in nested keys")
    void buildObjectUri_encodesSafelyWithoutDoubleEncoding() {
        // Standard nested key
        URI standardUri = storageService.buildObjectUri("authenticated", "user-123/doc-456.pdf");
        assertThat(standardUri.toString()).isEqualTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/user-123/doc-456.pdf");

        // Key with spaces
        URI spaceKeyUri = storageService.buildObjectUri("standard", "user 123/my research notes.pdf");
        assertThat(spaceKeyUri.toString()).isEqualTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/user%20123/my%20research%20notes.pdf");

        // Key with reserved characters (+ and #)
        URI reservedKeyUri = storageService.buildObjectUri("standard", "user-123/c++#guide.pdf");
        assertThat(reservedKeyUri.toString()).contains("c++%23guide.pdf");

        // Bucket with spaces
        SupabaseStorageService spaceBucketService = new SupabaseStorageService(
                SUPABASE_URL, SUPABASE_KEY, "my custom bucket", 1024, restTemplate
        );
        URI bucketSpaceUri = spaceBucketService.buildObjectUri("standard", "user-123/doc.pdf");
        assertThat(bucketSpaceUri.toString()).isEqualTo(SUPABASE_URL + "/storage/v1/object/my%20custom%20bucket/user-123/doc.pdf");

        // Legacy flat key
        URI legacyUri = storageService.buildObjectUri("authenticated", "d5d36e78-e5ff-4a47-a8a5-7b0a3c2bf962.pdf");
        assertThat(legacyUri.toString()).isEqualTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/d5d36e78-e5ff-4a47-a8a5-7b0a3c2bf962.pdf");
    }

    @Test
    @DisplayName("Uploads PDF to Supabase private bucket with user namespace and auth headers")
    void uploadToSupabase_succeeds() {
        UUID userId = UUID.randomUUID();
        byte[] pdfBytes = "%PDF-1.4 Mock PDF Content".getBytes();

        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + userId + "/"
                )))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("apikey", SUPABASE_KEY))
                .andExpect(header("Authorization", "Bearer " + SUPABASE_KEY))
                .andExpect(header("Content-Type", MediaType.APPLICATION_PDF_VALUE))
                .andExpect(header("x-upsert", "true"))
                .andRespond(withSuccess("{\"Key\":\"" + BUCKET_NAME + "/" + userId + "/doc.pdf\"}", MediaType.APPLICATION_JSON));

        String storageKey = storageService.storeFile(userId, pdfBytes);

        assertThat(storageKey).startsWith(userId + "/");
        assertThat(storageKey).endsWith(".pdf");
        mockServer.verify();
    }

    @Test
    @DisplayName("Downloads PDF bytes from Supabase authenticated endpoint")
    void downloadFromSupabase_succeeds() {
        String storageKey = "user-abc-123/sample.pdf";
        byte[] expectedBytes = "%PDF-1.4 Mock Downloaded PDF Content".getBytes();

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header("apikey", SUPABASE_KEY))
                .andExpect(header("Authorization", "Bearer " + SUPABASE_KEY))
                .andRespond(withSuccess(expectedBytes, MediaType.APPLICATION_OCTET_STREAM));

        byte[] loadedBytes = storageService.loadFile(storageKey);

        assertThat(loadedBytes).isEqualTo(expectedBytes);
        mockServer.verify();
    }

    @Test
    @DisplayName("Falls back to standard object endpoint when authenticated endpoint returns 404")
    void download_fallbackToStandardEndpoint_succeeds() {
        String storageKey = "user-abc-123/sample.pdf";
        byte[] expectedBytes = "%PDF-1.4 Fallback Bytes".getBytes();

        // 1st request to authenticated returns 404
        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // 2nd request to fallback object endpoint succeeds
        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withSuccess(expectedBytes, MediaType.APPLICATION_OCTET_STREAM));

        byte[] loadedBytes = storageService.loadFile(storageKey);

        assertThat(loadedBytes).isEqualTo(expectedBytes);
        mockServer.verify();
    }

    @Test
    @DisplayName("Missing legacy flat UUID key in Supabase returns informative re-upload guidance")
    void loadFile_missingLegacyKey_returnsInformativeException() {
        String legacyKey = "d5d36e78-e5ff-4a47-a8a5-7b0a3c2bf962.pdf";

        // Both authenticated and fallback endpoints return 404
        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/" + legacyKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + legacyKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> storageService.loadFile(legacyKey))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("originally stored on the local filesystem and must be migrated or re-uploaded");

        mockServer.verify();
    }

    @Test
    @DisplayName("Missing user-namespaced key in Supabase returns standard not found exception")
    void loadFile_missingHierarchicalKey_returnsStandardException() {
        String storageKey = "user-123/d5d36e78-e5ff-4a47-a8a5-7b0a3c2bf962.pdf";

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        assertThatThrownBy(() -> storageService.loadFile(storageKey))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("Stored document file not found in Supabase storage")
                .hasMessageNotContaining("originally stored on the local filesystem");

        mockServer.verify();
    }

    @Test
    @DisplayName("Deletes PDF object from Supabase bucket")
    void deleteFromSupabase_succeeds() {
        String storageKey = "user-abc-123/to-delete.pdf";

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", SUPABASE_KEY))
                .andExpect(header("Authorization", "Bearer " + SUPABASE_KEY))
                .andRespond(withSuccess("{\"message\":\"Successfully deleted\"}", MediaType.APPLICATION_JSON));

        storageService.deleteFile(storageKey);

        mockServer.verify();
    }

    @Test
    @DisplayName("Delete is idempotent when Supabase returns 404")
    void deleteFromSupabase_404IsIdempotent() {
        String storageKey = "user-abc-123/missing.pdf";

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        // Should not throw
        storageService.deleteFile(storageKey);

        mockServer.verify();
    }

    @Test
    @DisplayName("Upload error (403 Forbidden) throws FileStorageException without leaking credentials")
    void uploadFailure_throwsFileStorageException() {
        UUID userId = UUID.randomUUID();
        byte[] pdfBytes = "%PDF-1.4 Content".getBytes();

        mockServer.expect(requestTo(org.hamcrest.Matchers.startsWith(
                        SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/"
                )))
                .andExpect(method(HttpMethod.POST))
                .andRespond(withStatus(HttpStatus.FORBIDDEN));

        assertThatThrownBy(() -> storageService.storeFile(userId, pdfBytes))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("403");

        mockServer.verify();
    }

    @Test
    @DisplayName("Download error (500 Internal Error) throws FileStorageException")
    void downloadFailure_throwsFileStorageException() {
        String storageKey = "user-abc-123/broken.pdf";

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/authenticated/" + BUCKET_NAME + "/" + storageKey))
                .andExpect(method(HttpMethod.GET))
                .andRespond(withStatus(HttpStatus.INTERNAL_SERVER_ERROR));

        assertThatThrownBy(() -> storageService.loadFile(storageKey))
                .isInstanceOf(FileStorageException.class)
                .hasMessageContaining("500");

        mockServer.verify();
    }

    @Test
    @DisplayName("Rejects path traversal and leading slash in storage key")
    void validateStorageKey_rejectsMaliciousKeys() {
        assertThatThrownBy(() -> storageService.loadFile("../secret.pdf"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> storageService.loadFile("/leading/slash.pdf"))
                .isInstanceOf(SecurityException.class);

        assertThatThrownBy(() -> storageService.loadFile(null))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    @DisplayName("Startup validation fails if SUPABASE_URL or SUPABASE_KEY is missing")
    void validateConfiguration_failsOnMissingProperties() {
        SupabaseStorageService noUrl = new SupabaseStorageService("", SUPABASE_KEY, BUCKET_NAME, 1024, restTemplate);
        assertThatThrownBy(noUrl::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPABASE_URL");

        SupabaseStorageService noKey = new SupabaseStorageService(SUPABASE_URL, "", BUCKET_NAME, 1024, restTemplate);
        assertThatThrownBy(noKey::validateConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SUPABASE_KEY");
    }

    @Test
    @DisplayName("URL normalization strips trailing slashes, /rest/v1, and /storage/v1 to produce canonical storage API URL")
    void sanitizeUrl_normalizesRootProjectUrl() {
        SupabaseStorageService withRestV1 = new SupabaseStorageService(
                "https://project123.supabase.co/rest/v1/", SUPABASE_KEY, BUCKET_NAME, 1024, restTemplate
        );
        URI uri1 = withRestV1.buildObjectUri("standard", "doc.pdf");
        assertThat(uri1.toString()).isEqualTo("https://project123.supabase.co/storage/v1/object/" + BUCKET_NAME + "/doc.pdf");

        SupabaseStorageService withStorageV1 = new SupabaseStorageService(
                "https://project123.supabase.co/storage/v1/", SUPABASE_KEY, BUCKET_NAME, 1024, restTemplate
        );
        URI uri2 = withStorageV1.buildObjectUri("authenticated", "u1/doc.pdf");
        assertThat(uri2.toString()).isEqualTo("https://project123.supabase.co/storage/v1/object/authenticated/" + BUCKET_NAME + "/u1/doc.pdf");
    }

    @Test
    @DisplayName("isLegacyLocalKey identifies flat UUID keys as local and user-namespaced keys as Supabase")
    void isLegacyLocalKey_distinguishesStorageProviders() {
        String legacyFlatKey = "9bbbb4b1-eb1f-4512-a899-2aa42d3d490a.pdf";
        String supabaseHierarchicalKey = "fd918638-d5de-4f2d-8515-6f1373f313c2/21871409-4d3a-4dcd-a713-4a428717c3ad.pdf";

        assertThat(storageService.isLegacyLocalKey(legacyFlatKey)).isTrue();
        assertThat(storageService.isLegacyLocalKey(supabaseHierarchicalKey)).isFalse();
        assertThat(storageService.isLegacyLocalKey(null)).isFalse();
        assertThat(storageService.isLegacyLocalKey("")).isFalse();
    }

    @Test
    @DisplayName("deleteFile for legacy local key routes to LocalStorageService without sending request to Supabase")
    void deleteFile_legacyLocalKey_deletesFromDiskAndSkipsSupabase(@TempDir Path tempDir) throws IOException {
        LocalStorageService localService = new LocalStorageService(tempDir, 1024 * 1024);
        localService.init();

        String localFileName = UUID.randomUUID() + ".pdf";
        Path localFilePath = tempDir.resolve(localFileName);
        Files.write(localFilePath, "%PDF-1.4 Mock local document".getBytes());
        assertThat(Files.exists(localFilePath)).isTrue();

        SupabaseStorageService serviceWithFallback = new SupabaseStorageService(
                SUPABASE_URL, SUPABASE_KEY, BUCKET_NAME, 1024 * 1024, restTemplate, localService
        );

        // Delete the legacy document
        serviceWithFallback.deleteFile(localFileName);

        // Verify physical file was deleted from disk
        assertThat(Files.exists(localFilePath)).isFalse();
        // Verify no HTTP requests were sent to Supabase mock server
        mockServer.verify();
    }

    @Test
    @DisplayName("loadFile for legacy local key loads bytes directly from local disk")
    void loadFile_legacyLocalKey_loadsFromDisk(@TempDir Path tempDir) throws IOException {
        LocalStorageService localService = new LocalStorageService(tempDir, 1024 * 1024);
        localService.init();

        String localFileName = UUID.randomUUID() + ".pdf";
        byte[] expectedBytes = "%PDF-1.4 Local File Content".getBytes();
        Path localFilePath = tempDir.resolve(localFileName);
        Files.write(localFilePath, expectedBytes);

        SupabaseStorageService serviceWithFallback = new SupabaseStorageService(
                SUPABASE_URL, SUPABASE_KEY, BUCKET_NAME, 1024 * 1024, restTemplate, localService
        );

        byte[] loadedBytes = serviceWithFallback.loadFile(localFileName);

        assertThat(loadedBytes).isEqualTo(expectedBytes);
        mockServer.verify();
    }

    @Test
    @DisplayName("deleteFile for Supabase hierarchical key deletes from Supabase bucket")
    void deleteFile_supabaseKey_deletesFromSupabase(@TempDir Path tempDir) {
        LocalStorageService localService = new LocalStorageService(tempDir, 1024 * 1024);
        localService.init();

        SupabaseStorageService serviceWithFallback = new SupabaseStorageService(
                SUPABASE_URL, SUPABASE_KEY, BUCKET_NAME, 1024 * 1024, restTemplate, localService
        );

        String supabaseKey = "user-123/" + UUID.randomUUID() + ".pdf";

        mockServer.expect(requestTo(SUPABASE_URL + "/storage/v1/object/" + BUCKET_NAME + "/" + supabaseKey))
                .andExpect(method(HttpMethod.DELETE))
                .andExpect(header("apikey", SUPABASE_KEY))
                .andExpect(header("Authorization", "Bearer " + SUPABASE_KEY))
                .andRespond(withSuccess("{\"message\":\"Successfully deleted\"}", MediaType.APPLICATION_JSON));

        serviceWithFallback.deleteFile(supabaseKey);

        mockServer.verify();
    }
}
