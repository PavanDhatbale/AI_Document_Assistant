-- ==============================================================================
-- AI Document Assistant - V3 Migration: Add Document Chunks Table
-- Persists extracted text chunks, page numbers, and token counts
-- ==============================================================================

CREATE TABLE document_chunks (
    id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    chunk_index INT NOT NULL,
    page_number INT NOT NULL,
    chunk_text MEDIUMTEXT NOT NULL,
    token_count INT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_document_chunks PRIMARY KEY (id),
    CONSTRAINT uk_doc_chunk_index UNIQUE (document_id, chunk_index),
    CONSTRAINT fk_chunks_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
);

CREATE INDEX idx_chunks_document_index ON document_chunks (document_id, chunk_index ASC);
CREATE INDEX idx_chunks_doc_page ON document_chunks (document_id, page_number);
