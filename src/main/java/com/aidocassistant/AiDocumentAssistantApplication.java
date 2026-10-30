package com.aidocassistant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

/**
 * Main entry point for the AI Document Assistant application.
 * Automatically loads key-value pairs from a local .env file if present,
 * without overriding existing system environment variables or JVM system properties.
 */
@SpringBootApplication
public class AiDocumentAssistantApplication {

    private static final Logger log = LoggerFactory.getLogger(AiDocumentAssistantApplication.class);

    public static void main(String[] args) {
        loadDotenvIfPresent();
        SpringApplication.run(AiDocumentAssistantApplication.class, args);
    }

    /**
     * Programmatically reads a .env file from the working directory if it exists,
     * populating System properties so that Spring Boot's property placeholder resolution
     * (${VAR:default}) seamlessly picks them up.
     */
    static void loadDotenvIfPresent() {
        loadDotenv(new File(".env"));
    }

    /**
     * Loads environment properties from the specified file into System properties.
     * Guaranteed safe behaviors:
     * - OS environment variables and existing JVM system properties always take precedence.
     * - UTF-8 character encoding is strictly used.
     * - Blank lines and comment lines (starting with #) are ignored.
     * - Optional 'export ' line prefix is safely stripped.
     * - Double-quoted and single-quoted values have enclosing quotes stripped while preserving internal characters.
     * - Double-quoted values support standard escape sequences (\n, \t, \r, \", \\).
     * - Unquoted values strip inline comments (e.g., KEY=value # comment).
     * - Values with '=' (such as Base64 strings) are fully preserved.
     * - Missing files return cleanly without error.
     * - Secret values and keys are never logged.
     *
     * @param envFile the .env file to load
     * @return map of key-value pairs loaded into System properties (useful for test assertions)
     */
    static Map<String, String> loadDotenv(File envFile) {
        Map<String, String> loaded = new HashMap<>();
        if (envFile == null || !envFile.exists() || !envFile.isFile()) {
            return loaded;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(envFile), StandardCharsets.UTF_8))) {
            String rawLine;
            while ((rawLine = reader.readLine()) != null) {
                String line = rawLine.trim();
                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                // Strip optional leading 'export '
                if (line.startsWith("export ") && line.length() > 7) {
                    line = line.substring(7).trim();
                }

                int eqIdx = line.indexOf('=');
                if (eqIdx > 0) {
                    String key = line.substring(0, eqIdx).trim();
                    String val = line.substring(eqIdx + 1).trim();

                    if (key.isEmpty()) {
                        continue;
                    }

                    // Handle quoted values vs unquoted values
                    if ((val.startsWith("\"") && val.endsWith("\"") && val.length() >= 2)) {
                        val = val.substring(1, val.length() - 1);
                        val = unescapeDoubleQuoted(val);
                    } else if (val.startsWith("'") && val.endsWith("'") && val.length() >= 2) {
                        val = val.substring(1, val.length() - 1);
                    } else {
                        // Unquoted value: strip inline comments if separated by space
                        int inlineCommentIdx = val.indexOf(" #");
                        if (inlineCommentIdx >= 0) {
                            val = val.substring(0, inlineCommentIdx).trim();
                        }
                    }

                    // Only set if not already defined in OS environment or JVM system properties
                    if (System.getProperty(key) == null && System.getenv(key) == null) {
                        System.setProperty(key, val);
                        loaded.put(key, val);
                    }
                }
            }
            if (!loaded.isEmpty()) {
                log.info("Loaded {} configuration property entries from local .env file", loaded.size());
            }
        } catch (IOException e) {
            log.warn("Failed to read .env file: {}", e.getMessage());
        }
        return loaded;
    }

    private static String unescapeDoubleQuoted(String s) {
        if (!s.contains("\\")) {
            return s;
        }
        StringBuilder sb = new StringBuilder(s.length());
        boolean escaped = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (escaped) {
                switch (c) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\"' -> sb.append('\"');
                    case '\\' -> sb.append('\\');
                    default -> {
                        sb.append('\\');
                        sb.append(c);
                    }
                }
                escaped = false;
            } else if (c == '\\') {
                escaped = true;
            } else {
                sb.append(c);
            }
        }
        if (escaped) {
            sb.append('\\');
        }
        return sb.toString();
    }
}
