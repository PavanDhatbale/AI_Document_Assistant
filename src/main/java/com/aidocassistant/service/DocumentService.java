package com.aidocassistant.service;

import com.aidocassistant.dto.BulkDeleteResponse;
import com.aidocassistant.dto.DocumentChunkResponse;
import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.dto.RetrievedChunk;
import com.aidocassistant.exception.DuplicateDocumentException;
import com.aidocassistant.exception.InvalidFileException;
import com.aidocassistant.exception.ResourceNotFoundException;
import com.aidocassistant.model.Document;
import com.aidocassistant.model.DocumentChunk;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.model.User;
import com.aidocassistant.repository.DocumentChunkRepository;
import com.aidocassistant.repository.DocumentRepository;
import com.aidocassistant.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service managing document upload, retrieval, duplicate detection, text extraction,
 * chunking, Gemini embedding generation, Qdrant vector indexing, and two-phase deletion.
 * Enforces strict user ownership across all operations.
 */
@Service
public class DocumentService {

    private static final Logger log = LoggerFactory.getLogger(DocumentService.class);

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final FileStorageService fileStorageService;
    private final DocumentChunkRepository documentChunkRepository;
    private final PdfProcessingService pdfProcessingService;
    private final DocumentChunker documentChunker;
    private final EmbeddingService embeddingService;
    private final VectorStoreService vectorStoreService;
    private final int staleProcessingTimeoutMinutes;

    // Concurrent in-memory lock map to prevent race conditions on double-click ingestion requests
    private final ConcurrentHashMap<UUID, Object> activeProcessingLocks = new ConcurrentHashMap<>();

    @org.springframework.beans.factory.annotation.Autowired
    public DocumentService(
            DocumentRepository documentRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            DocumentChunkRepository documentChunkRepository,
            PdfProcessingService pdfProcessingService,
            DocumentChunker documentChunker,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService,
            @org.springframework.beans.factory.annotation.Value("${app.rag.stale-processing-timeout-minutes:15}") int staleProcessingTimeoutMinutes
    ) {
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.fileStorageService = fileStorageService;
        this.documentChunkRepository = documentChunkRepository;
        this.pdfProcessingService = pdfProcessingService;
        this.documentChunker = documentChunker;
        this.embeddingService = embeddingService;
        this.vectorStoreService = vectorStoreService;
        this.staleProcessingTimeoutMinutes = staleProcessingTimeoutMinutes > 0 ? staleProcessingTimeoutMinutes : 15;
    }

    public DocumentService(
            DocumentRepository documentRepository,
            UserRepository userRepository,
            FileStorageService fileStorageService,
            DocumentChunkRepository documentChunkRepository,
            PdfProcessingService pdfProcessingService,
            DocumentChunker documentChunker,
            EmbeddingService embeddingService,
            VectorStoreService vectorStoreService
    ) {
        this(documentRepository, userRepository, fileStorageService, documentChunkRepository,
                pdfProcessingService, documentChunker, embeddingService, vectorStoreService, 15);
    }

    /**
     * Uploads and persists a new PDF document for the authenticated user.
     * Validates file integrity, calculates SHA-256 hash, checks for duplicates,
     * stores the file in private storage, and records metadata in the database.
     */
    public DocumentResponse uploadDocument(UUID userId, MultipartFile file) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found with ID: " + userId));

        byte[] fileBytes;
        try {
            fileBytes = file.getBytes();
        } catch (IOException e) {
            throw new InvalidFileException("Could not read uploaded file bytes", e);
        }

        // Multi-layer validation (PDFBox structural check, header magic bytes, size, mime)
        fileStorageService.validateFile(file, fileBytes);
        int totalPages = fileStorageService.extractPageCount(fileBytes);
        String contentHash = fileStorageService.calculateSha256(fileBytes);

        // Enforce user-scoped duplicate prevention (different users may upload identical files)
        Optional<Document> duplicate = documentRepository.findByUserIdAndContentHash(userId, contentHash);
        if (duplicate.isPresent()) {
            throw new DuplicateDocumentException("A document with identical content has already been uploaded by this user ('"
                    + duplicate.get().getOriginalFileName() + "')");
        }

        // Store file with server-generated UUID filename outside public web root
        String storageFileName = fileStorageService.storeFile(fileBytes);

        // Create document record in PENDING status (ready for ingestion pipeline)
        Document document = new Document(
                UUID.randomUUID(),
                user,
                file.getOriginalFilename(),
                storageFileName,
                contentHash,
                file.getSize()
        );
        document.setTotalPages(totalPages);
        document.setStatus(DocumentStatus.PENDING);

        try {
            Document saved = documentRepository.save(document);
            log.info("Document successfully uploaded: id={}, user={}, pages={}, size={}",
                    saved.getId(), userId, totalPages, saved.getFileSizeBytes());
            return DocumentResponse.fromEntity(saved);
        } catch (Exception e) {
            log.error("Database persistence failed for document {}. Cleaning up stored file: {}",
                    storageFileName, e.getMessage());
            fileStorageService.cleanupFileQuietly(storageFileName);
            throw e;
        }
    }

    /**
     * Lists all documents belonging to the authenticated user, sorted by creation date descending.
     */
    @Transactional(readOnly = true)
    public List<DocumentResponse> listUserDocuments(UUID userId) {
        return documentRepository.findByUserIdOrderByCreatedAtDesc(userId).stream()
                .map(DocumentResponse::fromEntity)
                .toList();
    }

    /**
     * Retrieves document metadata for an owned document.
     * Throws ResourceNotFoundException if the document does not exist or belongs to another user.
     */
    @Transactional(readOnly = true)
    public DocumentResponse getDocumentMetadata(UUID userId, UUID documentId) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));
        return DocumentResponse.fromEntity(document);
    }

    /**
     * Deletes an owned document using non-XA two-phase deletion:
     * 1. Marks document as DELETING in relational database and flushes immediately.
     * 2. Purges all vector points from Qdrant matching user_id AND document_id.
     *    - If Qdrant deletion fails: status is transitioned to DELETION_FAILED, and physical file/DB are preserved.
     * 3. Deletes physical file on disk.
     *    - If physical file deletion fails: status is transitioned to DELETION_FAILED, and DB record is preserved.
     * 4. Deletes database entity (cascades remove chunks, sources, and associations).
     *
     * Calling deleteDocument on a document in DELETING or DELETION_FAILED state is safe and retries the cleanup.
     */
    @Transactional(noRollbackFor = {IllegalStateException.class, com.aidocassistant.exception.FileStorageException.class})
    public void deleteDocument(UUID userId, UUID documentId) {
        Document document = documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));

        String storageFileName = document.getStorageFileName();

        // Phase 1: Mark status as DELETING and commit state to DB
        document.setStatus(DocumentStatus.DELETING);
        document.setErrorMessage(null);
        documentRepository.saveAndFlush(document);

        // Phase 2: Purge vector points from Qdrant
        try {
            vectorStoreService.deleteDocumentVectors(userId, documentId);
        } catch (Exception e) {
            log.error("Vector deletion failed in Qdrant for document {}: {}", documentId, e.getMessage());
            document.setStatus(DocumentStatus.DELETION_FAILED);
            document.setErrorMessage("Failed to delete vectors from vector store: " + e.getMessage());
            documentRepository.saveAndFlush(document);
            throw new IllegalStateException("Failed to delete document vectors from vector store", e);
        }

        // Phase 3: Delete physical file on disk
        try {
            fileStorageService.deleteFile(storageFileName);
        } catch (Exception e) {
            log.error("Physical file deletion failed for document {}: {}", documentId, e.getMessage());
            document.setStatus(DocumentStatus.DELETION_FAILED);
            document.setErrorMessage("Failed to delete physical file: " + e.getMessage());
            documentRepository.saveAndFlush(document);
            throw new com.aidocassistant.exception.FileStorageException("Failed to delete document file from storage: " + e.getMessage(), e);
        }

        // Phase 4: Delete MySQL record
        documentRepository.delete(document);

        log.info("Document deleted successfully: id={}, user={}", documentId, userId);
    }

    /**
     * Bulk deletes multiple owned documents.
     * Verifies user ownership on each document before executing the two-phase deletion workflow.
     * Returns an outcome summary with counts of successfully deleted and failed IDs.
     */
    public BulkDeleteResponse bulkDeleteDocuments(UUID userId, List<UUID> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return new BulkDeleteResponse(0, List.of(), List.of(), "No documents provided for deletion");
        }

        List<UUID> deletedIds = new java.util.ArrayList<>();
        List<UUID> failedIds = new java.util.ArrayList<>();

        for (UUID docId : documentIds) {
            try {
                Optional<Document> docOpt = documentRepository.findByIdAndUserId(docId, userId);
                if (docOpt.isEmpty()) {
                    log.warn("Bulk delete skipped document {} - not owned by user {}", docId, userId);
                    failedIds.add(docId);
                    continue;
                }

                deleteDocument(userId, docId);
                deletedIds.add(docId);
            } catch (Exception e) {
                log.error("Failed to delete document {} during bulk deletion: {}", docId, e.getMessage());
                failedIds.add(docId);
            }
        }

        String message = String.format("Successfully deleted %d of %d documents", deletedIds.size(), documentIds.size());
        return new BulkDeleteResponse(deletedIds.size(), deletedIds, failedIds, message);
    }

    /**
     * Executes the complete end-to-end ingestion pipeline:
     * 1. Concurrency guard check (rejects if already PROCESSING).
     * 2. Transitions status: PENDING -> PROCESSING.
     * 3. Extracts text page-by-page via PDFBox 3.x.
     * 4. Chunks text via token-aware DocumentChunker.
     * 5. Persists chunks in document_chunks table.
     * 6. Generates 768-dim embeddings via Google Gemini.
     * 7. Upserts vector points into Qdrant vector database.
     * 8. Transitions status to READY only upon verified Qdrant confirmation.
     * 9. On failure: rolls back partial chunks, cleans up partial vectors, and marks FAILED.
     */
    @Transactional
    public DocumentResponse processDocument(UUID userId, UUID documentId) {
        // Concurrency guard 1: In-memory lock per document ID (guards rapid duplicate requests within same JVM instance)
        Object lockToken = new Object();
        if (activeProcessingLocks.putIfAbsent(documentId, lockToken) != null) {
            throw new IllegalStateException("Document " + documentId + " is currently being processed");
        }

        try {
            // Concurrency guard 2: Database-level atomic conditional transition (guards multi-instance deployments)
            Instant staleThreshold = Instant.now().minus(staleProcessingTimeoutMinutes, ChronoUnit.MINUTES);
            List<DocumentStatus> claimableStatuses = List.of(DocumentStatus.PENDING, DocumentStatus.FAILED, DocumentStatus.READY);

            int claimed = documentRepository.claimDocumentForProcessing(
                    documentId, userId, DocumentStatus.PROCESSING, claimableStatuses, DocumentStatus.PROCESSING, staleThreshold
            );

            if (claimed == 0) {
                // Determine reason for claim failure to return clear, actionable feedback
                Document existing = documentRepository.findByIdAndUserId(documentId, userId)
                        .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));

                if (existing.getStatus() == DocumentStatus.PROCESSING) {
                    throw new IllegalStateException("Document " + documentId + " is currently being processed by another instance");
                }
                if (existing.getStatus() == DocumentStatus.DELETING || existing.getStatus() == DocumentStatus.DELETION_FAILED) {
                    throw new IllegalStateException("Cannot process document in deletion state: " + existing.getStatus());
                }
                throw new IllegalStateException("Document " + documentId + " cannot be processed in status: " + existing.getStatus());
            }

            Document document = documentRepository.findByIdAndUserId(documentId, userId)
                    .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));

            // Safe replacement strategy: clear any pre-existing chunks from prior runs
            documentChunkRepository.deleteByDocumentId(documentId);

            try {
                // Step 1: Text extraction
                List<PdfProcessingService.PageText> pages = pdfProcessingService.extractPages(document.getStorageFileName());

                // Step 2: Page-aware chunking
                List<DocumentChunker.RawChunk> rawChunks = documentChunker.chunkPages(pages);

                if (rawChunks.isEmpty()) {
                    throw new InvalidFileException("No usable text could be extracted from document");
                }

                // Step 3: Persist relational chunks
                final Document finalDoc = document;
                List<DocumentChunk> entityChunks = rawChunks.stream()
                        .map(rc -> new DocumentChunk(
                                UUID.randomUUID(),
                                finalDoc,
                                rc.chunkIndex(),
                                rc.pageNumber(),
                                rc.text(),
                                rc.tokenCount()
                        ))
                        .toList();

                List<DocumentChunk> savedChunks = documentChunkRepository.saveAll(entityChunks);

                // Step 4: Generate Gemini vector embeddings (768-dim, MRL)
                List<String> chunkTexts = savedChunks.stream()
                        .map(DocumentChunk::getChunkText)
                        .toList();
                List<float[]> embeddings = embeddingService.generateEmbeddings(chunkTexts);

                // Step 5: Upsert vector points into Qdrant
                vectorStoreService.indexChunks(userId, documentId, savedChunks, embeddings);

                // Step 6: Mark document as READY only after Qdrant confirms
                document.setTotalPages(pages.size());
                document.setTotalChunks(savedChunks.size());
                document.setStatus(DocumentStatus.READY);
                document.setErrorMessage(null);
                Document readyDoc = documentRepository.save(document);

                log.info("Document successfully indexed and marked READY: id={}, pages={}, chunks={}",
                        documentId, pages.size(), savedChunks.size());

                return DocumentResponse.fromEntity(readyDoc);
            } catch (Exception e) {
                log.error("Failed to process document {}: {}", documentId, e.getMessage());

                // Vector cleanup for partial writes
                try {
                    vectorStoreService.deleteDocumentVectors(userId, documentId);
                } catch (Exception ex) {
                    log.warn("Vector cleanup failed for incomplete document {}: {}", documentId, ex.getMessage());
                }

                // Chunk cleanup
                try {
                    documentChunkRepository.deleteByDocumentId(documentId);
                } catch (Exception ex) {
                    log.warn("Failed to clean up partial chunks for document {}: {}", documentId, ex.getMessage());
                }

                // Mark FAILED
                document.setStatus(DocumentStatus.FAILED);
                document.setErrorMessage(e.getMessage());
                Document failedDoc = documentRepository.save(document);

                return DocumentResponse.fromEntity(failedDoc);
            }
        } finally {
            activeProcessingLocks.remove(documentId);
        }
    }

    /**
     * Retrieves all extracted chunks for an owned document, sorted by chunkIndex ASC.
     */
    @Transactional(readOnly = true)
    public List<DocumentChunkResponse> getDocumentChunks(UUID userId, UUID documentId) {
        // Enforce ownership check
        documentRepository.findByIdAndUserId(documentId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Document not found with ID: " + documentId));

        return documentChunkRepository.findByDocumentIdOrderByChunkIndexAsc(documentId).stream()
                .map(DocumentChunkResponse::fromEntity)
                .toList();
    }

    /**
     * Executes multi-tenant vector search and filters out any candidate chunks
     * belonging to documents that are not in READY status.
     */
    @Transactional(readOnly = true)
    public List<RetrievedChunk> searchSimilarChunks(
            UUID userId,
            List<UUID> documentIds,
            float[] queryVector,
            int topK,
            double similarityThreshold
    ) {
        List<RetrievedChunk> candidateChunks = vectorStoreService.searchSimilar(
                userId, documentIds, queryVector, topK, similarityThreshold
        );

        if (candidateChunks.isEmpty()) {
            return List.of();
        }

        // Verify document readiness against relational database
        Set<UUID> candidateDocIds = candidateChunks.stream()
                .map(RetrievedChunk::documentId)
                .collect(Collectors.toSet());

        List<Document> documents = documentRepository.findAllById(candidateDocIds);
        Set<UUID> readyDocIds = documents.stream()
                .filter(d -> d.getStatus() == DocumentStatus.READY && d.getUser().getId().equals(userId))
                .map(Document::getId)
                .collect(Collectors.toSet());

        return candidateChunks.stream()
                .filter(chunk -> readyDocIds.contains(chunk.documentId()))
                .toList();
    }
}
