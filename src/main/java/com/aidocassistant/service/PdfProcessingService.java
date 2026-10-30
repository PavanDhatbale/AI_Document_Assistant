package com.aidocassistant.service;

import com.aidocassistant.exception.InvalidFileException;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service responsible for page-by-page PDF text extraction using Apache PDFBox 3.x.
 * Preserves 1-based page numbers, applies lossless text normalization,
 * and safely handles blank, encrypted, or corrupted PDFs.
 */
@Service
public class PdfProcessingService {

    private static final Logger log = LoggerFactory.getLogger(PdfProcessingService.class);

    private final FileStorageService fileStorageService;

    public record PageText(int pageNumber, String text) {
        public boolean hasText() {
            return text != null && !text.isBlank();
        }
    }

    public PdfProcessingService(FileStorageService fileStorageService) {
        this.fileStorageService = fileStorageService;
    }

    /**
     * Extracts text from the stored PDF document page-by-page.
     *
     * @param storageFileName the server-controlled storage filename in private storage
     * @return an ordered list of PageText records preserving 1-based page numbers
     * @throws InvalidFileException if the file is encrypted, corrupt, contains zero pages,
     *                              or contains zero extractable text across all pages.
     */
    public List<PageText> extractPages(String storageFileName) {
        byte[] fileBytes = fileStorageService.loadFile(storageFileName);
        return extractPagesFromBytes(fileBytes);
    }

    /**
     * Extracts text from raw PDF bytes.
     */
    public List<PageText> extractPagesFromBytes(byte[] fileBytes) {
        if (fileBytes == null || fileBytes.length == 0) {
            throw new InvalidFileException("Cannot extract text from empty PDF file bytes");
        }

        try (PDDocument document = Loader.loadPDF(fileBytes)) {
            if (document.isEncrypted()) {
                throw new InvalidFileException("Encrypted or password-protected PDF files cannot be processed");
            }

            int totalPages = document.getNumberOfPages();
            if (totalPages == 0) {
                throw new InvalidFileException("PDF document contains zero pages");
            }

            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true); // Maintain natural reading order

            List<PageText> pages = new ArrayList<>(totalPages);
            boolean hasAnyExtractableText = false;

            for (int pageNum = 1; pageNum <= totalPages; pageNum++) {
                stripper.setStartPage(pageNum);
                stripper.setEndPage(pageNum);

                String rawText = stripper.getText(document);
                String normalizedText = normalizeText(rawText);

                if (!normalizedText.isBlank()) {
                    hasAnyExtractableText = true;
                }

                pages.add(new PageText(pageNum, normalizedText));
            }

            if (!hasAnyExtractableText) {
                // OCR is not supported; scanned or image-only PDFs with no embedded text are rejected cleanly
                throw new InvalidFileException(
                        "PDF contains no extractable text. Scanned or image-only documents require OCR, which is not yet supported."
                );
            }

            log.info("Extracted text across {} pages (pages with text: {})",
                    totalPages, pages.stream().filter(PageText::hasText).count());

            return pages;
        } catch (org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException e) {
            log.warn("Attempted to parse encrypted/password-protected PDF: {}", e.getMessage());
            throw new InvalidFileException("Encrypted or password-protected PDF files cannot be processed");
        } catch (IOException e) {
            log.error("Failed to parse PDF document with PDFBox: {}", e.getMessage());
            throw new InvalidFileException("Malformed or unreadable PDF: " + e.getMessage(), e);
        }
    }

    /**
     * Normalizes extracted page text without destroying semantic punctuation or formatting:
     * - Strips null bytes (\u0000)
     * - Normalizes carriage returns to standard newlines (\n)
     * - Strips unprintable control characters (retains \n and \t)
     * - Collapses redundant horizontal spacing
     * - Trims excessive consecutive blank lines (max 2 newlines)
     */
    public String normalizeText(String rawText) {
        if (rawText == null || rawText.isBlank()) {
            return "";
        }

        String text = rawText.replace("\u0000", " ")
                .replace("\r\n", "\n")
                .replace("\r", "\n");

        // Remove unprintable control characters while preserving newline and tab
        text = text.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");

        // Collapse multiple horizontal spaces and tabs to a single space
        text = text.replaceAll("[ \\t\\x0B\\f]+", " ");

        // Collapse three or more newlines into double newlines (paragraph boundary)
        text = text.replaceAll("\\n{3,}", "\n\n");

        return text.trim();
    }
}
