package com.aidocassistant.service;

import com.aidocassistant.exception.InvalidFileException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Base abstract class providing common validation and inspection methods
 * for all storage service implementations.
 */
public abstract class AbstractStorageService implements StorageService {

    protected static final byte[] PDF_MAGIC_BYTES = new byte[]{0x25, 0x50, 0x44, 0x46, 0x2D}; // "%PDF-"

    protected final long maxFileSizeBytes;

    protected AbstractStorageService(long maxFileSizeBytes) {
        this.maxFileSizeBytes = maxFileSizeBytes;
    }

    @Override
    public void validateFile(MultipartFile file, byte[] fileBytes) {
        if (file == null || file.isEmpty() || fileBytes == null || fileBytes.length == 0) {
            throw new InvalidFileException("Uploaded file is empty or missing");
        }

        if (fileBytes.length > maxFileSizeBytes) {
            throw new InvalidFileException("File size exceeds maximum permitted limit of " + (maxFileSizeBytes / (1024 * 1024)) + "MB");
        }

        String originalFilename = file.getOriginalFilename();
        if (originalFilename == null || originalFilename.isBlank()) {
            throw new InvalidFileException("Original filename cannot be empty");
        }

        String cleanedFilename = StringUtils.cleanPath(originalFilename);
        if (cleanedFilename.contains("..")) {
            throw new InvalidFileException("Path traversal characters in filename are not permitted");
        }

        if (!cleanedFilename.toLowerCase().endsWith(".pdf")) {
            throw new InvalidFileException("Only PDF files are supported");
        }

        String contentType = file.getContentType();
        if (contentType != null && !contentType.equalsIgnoreCase("application/pdf") && !contentType.equalsIgnoreCase("application/x-pdf")) {
            throw new InvalidFileException("Declared content type must be application/pdf");
        }

        if (!hasPdfHeader(fileBytes)) {
            throw new InvalidFileException("Invalid PDF file: missing %PDF- header signature");
        }

        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            if (doc.isEncrypted()) {
                throw new InvalidFileException("Encrypted or password-protected PDF files are not supported");
            }
            if (doc.getNumberOfPages() <= 0) {
                throw new InvalidFileException("PDF file contains no readable pages");
            }
        } catch (IOException e) {
            throw new InvalidFileException("Malformed or unreadable PDF: " + e.getMessage(), e);
        }
    }

    @Override
    public int extractPageCount(byte[] fileBytes) {
        try (PDDocument doc = Loader.loadPDF(fileBytes)) {
            return doc.getNumberOfPages();
        } catch (IOException e) {
            throw new InvalidFileException("Failed to read PDF page count: " + e.getMessage(), e);
        }
    }

    @Override
    public String calculateSha256(byte[] fileBytes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(fileBytes);
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 algorithm unavailable", e);
        }
    }

    @Override
    public String storeFile(byte[] fileBytes) {
        return storeFile((UUID) null, fileBytes);
    }

    public long getMaxFileSizeBytes() {
        return maxFileSizeBytes;
    }

    protected boolean hasPdfHeader(byte[] bytes) {
        if (bytes == null || bytes.length < PDF_MAGIC_BYTES.length) {
            return false;
        }
        for (int i = 0; i < PDF_MAGIC_BYTES.length; i++) {
            if (bytes[i] != PDF_MAGIC_BYTES[i]) {
                return false;
            }
        }
        return true;
    }
}
