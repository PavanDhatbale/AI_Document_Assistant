package com.aidocassistant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Context-load smoke test and comprehensive unit test suite for the custom .env loader.
 */
@SpringBootTest
@ActiveProfiles("test")
class AiDocumentAssistantApplicationTests {

    @Test
    @DisplayName("Verify Spring application context loads cleanly")
    void contextLoads() {
        // Assertion passes if Spring ApplicationContext initializes without exceptions
    }

    @Test
    @DisplayName("1. .env loader correctly parses plain, quoted, and exported values")
    void testDotenvParsing(@TempDir Path tempDir) throws IOException {
        File envFile = tempDir.resolve(".env.sample").toFile();
        String content = """
                # Top level comment
                PLAIN_VAR=simple_value
                export EXPORTED_VAR=exported_value
                DOUBLE_QUOTED="quoted with spaces"
                SINGLE_QUOTED='single quoted value'
                WITH_EQUALS=base64_encoded_value==padding
                INLINE_COMMENT=unquoted_value # this should be stripped
                QUOTED_COMMENT="quoted # this should remain"
                ESCAPED_NEWLINE="line1\\nline2"
                """;

        try (FileOutputStream fos = new FileOutputStream(envFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }

        Map<String, String> loaded = AiDocumentAssistantApplication.loadDotenv(envFile);

        try {
            assertEquals("simple_value", loaded.get("PLAIN_VAR"));
            assertEquals("exported_value", loaded.get("EXPORTED_VAR"));
            assertEquals("quoted with spaces", loaded.get("DOUBLE_QUOTED"));
            assertEquals("single quoted value", loaded.get("SINGLE_QUOTED"));
            assertEquals("base64_encoded_value==padding", loaded.get("WITH_EQUALS"));
            assertEquals("unquoted_value", loaded.get("INLINE_COMMENT"));
            assertEquals("quoted # this should remain", loaded.get("QUOTED_COMMENT"));
            assertEquals("line1\nline2", loaded.get("ESCAPED_NEWLINE"));
        } finally {
            // Clean up system properties set by test
            loaded.keySet().forEach(System::clearProperty);
        }
    }

    @Test
    @DisplayName("2. Existing JVM System properties take precedence over .env entries")
    void testSystemPropertyPrecedence(@TempDir Path tempDir) throws IOException {
        String existingKey = "EXISTING_PREDEFINED_PROP_" + System.currentTimeMillis();
        System.setProperty(existingKey, "jvm_original_value");

        File envFile = tempDir.resolve(".env.precedence").toFile();
        String content = existingKey + "=overridden_env_value\n";

        try (FileOutputStream fos = new FileOutputStream(envFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }

        try {
            Map<String, String> loaded = AiDocumentAssistantApplication.loadDotenv(envFile);
            assertThat(loaded).doesNotContainKey(existingKey);
            assertEquals("jvm_original_value", System.getProperty(existingKey));
        } finally {
            System.clearProperty(existingKey);
        }
    }

    @Test
    @DisplayName("3. Missing .env file is handled gracefully without error")
    void testMissingFileHandledGracefully(@TempDir Path tempDir) {
        File nonExistent = tempDir.resolve(".env.does_not_exist").toFile();
        Map<String, String> loaded = AiDocumentAssistantApplication.loadDotenv(nonExistent);
        assertThat(loaded).isEmpty();
    }

    @Test
    @DisplayName("4. Empty lines and comment-only lines are safely skipped")
    void testEmptyAndCommentLines(@TempDir Path tempDir) throws IOException {
        File envFile = tempDir.resolve(".env.empty").toFile();
        String content = """
                
                # Full line comment 1
                   # Comment with leading spaces
                
                """;

        try (FileOutputStream fos = new FileOutputStream(envFile)) {
            fos.write(content.getBytes(StandardCharsets.UTF_8));
        }

        Map<String, String> loaded = AiDocumentAssistantApplication.loadDotenv(envFile);
        assertThat(loaded).isEmpty();
    }
}
