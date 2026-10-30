package com.aidocassistant.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Service that partitions extracted PDF page text into semantically coherent,
 * token-aware chunks while strictly preserving page references for citations.
 *
 * <p><strong>Tokenization Approximation &amp; Limitations:</strong>
 * Token counts are estimated using the standard industry heuristic of 1 token ≈ 4 characters
 * (equivalent to ~0.75 words for natural English prose). While exact token counts vary
 * between tokenizers (e.g. Gemini SentencePiece vs. OpenAI tiktoken), this approach guarantees
 * consistent boundary calculation, eliminates chunk truncation, and avoids platform-dependent
 * native bindings.
 */
@Service
public class DocumentChunker {

    private static final Logger log = LoggerFactory.getLogger(DocumentChunker.class);

    private final int chunkSizeTokens;
    private final int chunkOverlapTokens;

    public record RawChunk(int chunkIndex, int pageNumber, String text, int tokenCount) {}

    public DocumentChunker(
            @Value("${app.rag.chunk-size-tokens:500}") int chunkSizeTokens,
            @Value("${app.rag.chunk-overlap-tokens:50}") int chunkOverlapTokens
    ) {
        if (chunkSizeTokens <= 0) {
            throw new IllegalArgumentException("chunkSizeTokens must be greater than 0");
        }
        if (chunkOverlapTokens < 0 || chunkOverlapTokens >= chunkSizeTokens) {
            throw new IllegalArgumentException("chunkOverlapTokens must be >= 0 and < chunkSizeTokens");
        }
        this.chunkSizeTokens = chunkSizeTokens;
        this.chunkOverlapTokens = chunkOverlapTokens;
    }

    /**
     * Splits a list of extracted page texts into ordered, non-empty chunks.
     * Pages without extractable text are skipped without emitting empty chunks.
     *
     * @param pages list of PageText items representing the document pages
     * @return ordered list of RawChunk records with continuous 0-based chunk indices
     */
    public List<RawChunk> chunkPages(List<PdfProcessingService.PageText> pages) {
        if (pages == null || pages.isEmpty()) {
            return Collections.emptyList();
        }

        List<RawChunk> allChunks = new ArrayList<>();
        int globalChunkIndex = 0;

        for (PdfProcessingService.PageText page : pages) {
            if (!page.hasText()) {
                continue;
            }

            List<String> segments = splitIntoSegments(page.text());
            if (segments.isEmpty()) {
                continue;
            }

            int start = 0;
            while (start < segments.size()) {
                int end = start;
                int currentTokens = 0;
                StringBuilder chunkContent = new StringBuilder();

                while (end < segments.size()) {
                    String segment = segments.get(end);
                    int segmentTokens = estimateTokens(segment);

                    // If chunk already has content and adding this segment exceeds limit, stop
                    if (currentTokens > 0 && (currentTokens + segmentTokens) > chunkSizeTokens) {
                        break;
                    }

                    if (chunkContent.length() > 0) {
                        chunkContent.append(" ");
                    }
                    chunkContent.append(segment);
                    currentTokens += segmentTokens;
                    end++;
                }

                String finalText = chunkContent.toString().trim();
                if (!finalText.isEmpty()) {
                    allChunks.add(new RawChunk(
                            globalChunkIndex++,
                            page.pageNumber(),
                            finalText,
                            estimateTokens(finalText)
                    ));
                }

                if (end >= segments.size()) {
                    // Page finished
                    break;
                }

                // Calculate overlap window: look backwards from 'end' to find starting point for next chunk
                int overlapTokens = 0;
                int nextStart = end;

                for (int i = end - 1; i > start; i--) {
                    int segTokens = estimateTokens(segments.get(i));
                    if (overlapTokens + segTokens <= chunkOverlapTokens) {
                        overlapTokens += segTokens;
                        nextStart = i;
                    } else {
                        break;
                    }
                }

                // Strictly ensure forward progression to prevent infinite loops
                if (nextStart <= start) {
                    nextStart = end;
                }

                start = nextStart;
            }
        }

        log.info("Produced {} chunks across {} input pages (target size: ~{} tokens, overlap: ~{} tokens)",
                allChunks.size(), pages.size(), chunkSizeTokens, chunkOverlapTokens);

        return allChunks;
    }

    /**
     * Splits page text into paragraph- and sentence-level segments.
     * Large sentences exceeding chunkSizeTokens are subdivided by word boundaries.
     */
    private List<String> splitIntoSegments(String text) {
        List<String> segments = new ArrayList<>();
        String[] paragraphs = text.split("\n\n+");

        for (String paragraph : paragraphs) {
            String cleanParagraph = paragraph.trim();
            if (cleanParagraph.isEmpty()) {
                continue;
            }

            // Split on sentence boundaries: periods, exclamation, or question marks followed by whitespace
            String[] sentences = cleanParagraph.split("(?<=[.!?])\\s+");
            for (String sentence : sentences) {
                String cleanSentence = sentence.trim();
                if (cleanSentence.isEmpty()) {
                    continue;
                }

                if (estimateTokens(cleanSentence) > chunkSizeTokens) {
                    // Long sentence exceeding chunk limit: split on word boundaries
                    segments.addAll(splitLongSentence(cleanSentence));
                } else {
                    segments.add(cleanSentence);
                }
            }
        }
        return segments;
    }

    /**
     * Splits an oversized sentence on word boundaries.
     */
    private List<String> splitLongSentence(String sentence) {
        List<String> result = new ArrayList<>();
        String[] words = sentence.split("\\s+");
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String word : words) {
            int wordTokens = estimateTokens(word);
            if (currentTokens > 0 && (currentTokens + wordTokens) > chunkSizeTokens) {
                result.add(current.toString().trim());
                current = new StringBuilder();
                currentTokens = 0;
            }
            if (current.length() > 0) {
                current.append(" ");
            }
            current.append(word);
            currentTokens += wordTokens;
        }

        if (current.length() > 0) {
            result.add(current.toString().trim());
        }

        return result;
    }

    /**
     * Estimates the token count of a given text segment.
     * Uses the standard character heuristic: 1 token ≈ 4 characters.
     */
    public int estimateTokens(String text) {
        if (text == null || text.isBlank()) {
            return 0;
        }
        return Math.max(1, (int) Math.ceil(text.trim().length() / 4.0));
    }

    public int getChunkSizeTokens() {
        return chunkSizeTokens;
    }

    public int getChunkOverlapTokens() {
        return chunkOverlapTokens;
    }
}
