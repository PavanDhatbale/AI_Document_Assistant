package com.aidocassistant.controller;

import com.aidocassistant.dto.BulkDeleteRequest;
import com.aidocassistant.dto.BulkDeleteResponse;
import com.aidocassistant.dto.DocumentChunkResponse;
import com.aidocassistant.dto.DocumentResponse;
import com.aidocassistant.model.DocumentStatus;
import com.aidocassistant.security.SecurityUserPrincipal;
import com.aidocassistant.service.DocumentService;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * Controller exposing endpoints for secure document management:
 * upload, listing, metadata lookup, and deletion.
 * All endpoints enforce ownership derived strictly from the authenticated JWT principal.
 */
@RestController
@RequestMapping("/api/documents")
public class DocumentController {

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    /**
     * Uploads and validates a PDF document for the current authenticated user.
     * Supports both POST /api/documents and POST /api/documents/upload.
     */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        DocumentResponse response = documentService.uploadDocument(principal.getId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<DocumentResponse> uploadDocumentAlias(
            @RequestParam("file") MultipartFile file,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        DocumentResponse response = documentService.uploadDocument(principal.getId(), file);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    /**
     * Lists documents owned by the authenticated user with optional search, status filtering,
     * sorting, and server-side pagination.
     */
    @GetMapping
    public ResponseEntity<List<DocumentResponse>> listDocuments(
            @RequestParam(name = "search", required = false) String search,
            @RequestParam(name = "status", required = false) String status,
            @RequestParam(name = "sortBy", required = false, defaultValue = "createdAt") String sortBy,
            @RequestParam(name = "sortDirection", required = false, defaultValue = "desc") String sortDirection,
            @RequestParam(name = "page", required = false) Integer page,
            @RequestParam(name = "size", required = false) Integer size,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        List<DocumentResponse> allDocuments = documentService.listUserDocuments(principal.getId());

        Stream<DocumentResponse> stream = allDocuments.stream();

        // 1. Filter by search query (filename)
        if (search != null && !search.trim().isEmpty()) {
            String lowerSearch = search.trim().toLowerCase();
            stream = stream.filter(d -> d.originalFileName() != null && d.originalFileName().toLowerCase().contains(lowerSearch));
        }

        // 2. Filter by status
        if (status != null && !status.trim().isEmpty() && !status.equalsIgnoreCase("ALL")) {
            String upperStatus = status.trim().toUpperCase();
            if (upperStatus.equals("PROCESSING")) {
                stream = stream.filter(d -> d.status() == DocumentStatus.PROCESSING || d.status() == DocumentStatus.PENDING);
            } else if (upperStatus.equals("FAILED")) {
                stream = stream.filter(d -> d.status() == DocumentStatus.FAILED || d.status() == DocumentStatus.DELETION_FAILED);
            } else {
                stream = stream.filter(d -> d.status() != null && d.status().name().equalsIgnoreCase(upperStatus));
            }
        }

        // 3. Sort by upload date (createdAt), filename (originalFileName), or file size (fileSizeBytes)
        boolean ascending = "asc".equalsIgnoreCase(sortDirection);
        Comparator<DocumentResponse> comparator;
        if ("originalFileName".equalsIgnoreCase(sortBy) || "filename".equalsIgnoreCase(sortBy)) {
            comparator = Comparator.comparing(
                    d -> d.originalFileName() != null ? d.originalFileName().toLowerCase() : "",
                    Comparator.naturalOrder()
            );
        } else if ("fileSizeBytes".equalsIgnoreCase(sortBy) || "size".equalsIgnoreCase(sortBy)) {
            comparator = Comparator.comparingLong(DocumentResponse::fileSizeBytes);
        } else {
            comparator = Comparator.comparing(
                    d -> d.createdAt() != null ? d.createdAt() : java.time.Instant.EPOCH,
                    Comparator.naturalOrder()
            );
        }

        if (!ascending) {
            comparator = comparator.reversed();
        }

        List<DocumentResponse> processedDocs = stream.sorted(comparator).toList();
        int totalElements = processedDocs.size();

        // 4. Optional server-side pagination
        if (page != null && size != null && size > 0 && page >= 0) {
            int fromIndex = page * size;
            List<DocumentResponse> paged;
            if (fromIndex >= totalElements) {
                paged = List.of();
            } else {
                int toIndex = Math.min(fromIndex + size, totalElements);
                paged = processedDocs.subList(fromIndex, toIndex);
            }
            int totalPages = (int) Math.ceil((double) totalElements / size);

            return ResponseEntity.ok()
                    .header("X-Total-Count", String.valueOf(totalElements))
                    .header("X-Total-Pages", String.valueOf(totalPages))
                    .header("X-Current-Page", String.valueOf(page))
                    .header("X-Page-Size", String.valueOf(size))
                    .body(paged);
        }

        return ResponseEntity.ok()
                .header("X-Total-Count", String.valueOf(totalElements))
                .body(processedDocs);
    }

    /**
     * Bulk deletes multiple owned documents with atomic verification per document.
     */
    @PostMapping("/bulk-delete")
    public ResponseEntity<BulkDeleteResponse> bulkDeleteDocuments(
            @Valid @RequestBody BulkDeleteRequest request,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        BulkDeleteResponse response = documentService.bulkDeleteDocuments(principal.getId(), request.documentIds());
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves metadata for an owned document.
     * Returns 404 if the document does not exist or belongs to another user.
     */
    @GetMapping("/{id}")
    public ResponseEntity<DocumentResponse> getDocumentMetadata(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        DocumentResponse document = documentService.getDocumentMetadata(principal.getId(), id);
        return ResponseEntity.ok(document);
    }

    /**
     * Deletes an owned document and its associated file on disk.
     * Returns 404 if the document does not exist or belongs to another user.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Map<String, String>> deleteDocument(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        documentService.deleteDocument(principal.getId(), id);
        return ResponseEntity.ok(Map.of(
                "message", "Document deleted successfully",
                "id", id.toString()
        ));
    }

    /**
     * Explicit retry endpoint for documents in DELETION_FAILED or DELETING state.
     * Retries Qdrant vector purge, file deletion, and database removal.
     */
    @PostMapping("/{id}/retry-delete")
    public ResponseEntity<Map<String, String>> retryDeleteDocument(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        documentService.deleteDocument(principal.getId(), id);
        return ResponseEntity.ok(Map.of(
                "message", "Document deletion retried successfully",
                "id", id.toString()
        ));
    }

    /**
     * Triggers PDF text extraction and chunking for an owned document.
     * Extracts text page-by-page, generates overlapping semantic chunks,
     * persists chunks in MySQL, and updates document totalPages and totalChunks.
     */
    @PostMapping("/{id}/process")
    public ResponseEntity<DocumentResponse> processDocument(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        DocumentResponse response = documentService.processDocument(principal.getId(), id);
        return ResponseEntity.ok(response);
    }

    /**
     * Retrieves all extracted chunks for an owned document.
     */
    @GetMapping("/{id}/chunks")
    public ResponseEntity<List<DocumentChunkResponse>> getDocumentChunks(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal SecurityUserPrincipal principal
    ) {
        List<DocumentChunkResponse> chunks = documentService.getDocumentChunks(principal.getId(), id);
        return ResponseEntity.ok(chunks);
    }
}
