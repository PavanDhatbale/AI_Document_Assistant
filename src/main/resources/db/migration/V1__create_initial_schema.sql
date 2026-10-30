-- ==============================================================================
-- AI Document Assistant - V1 Initial Relational Schema
-- Supports users, documents, conversations, join table, messages, and citations
-- Designed for MySQL 8+ (InnoDB, UTF-8 MB4) and ANSI SQL compatibility
-- ==============================================================================

-- 1. Users Table
CREATE TABLE users (
    id CHAR(36) NOT NULL,
    email VARCHAR(255) NOT NULL,
    password_hash VARCHAR(255) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    phone_number VARCHAR(30) NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_email UNIQUE (email)
);

-- 2. Documents Table
CREATE TABLE documents (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    original_file_name VARCHAR(255) NOT NULL,
    storage_file_name VARCHAR(255) NOT NULL,
    content_hash VARCHAR(64) NOT NULL,
    file_size_bytes BIGINT NOT NULL,
    total_pages INT NULL,
    total_chunks INT NULL,
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING',
    error_message TEXT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_documents PRIMARY KEY (id),
    CONSTRAINT uk_documents_storage_file_name UNIQUE (storage_file_name),
    CONSTRAINT fk_documents_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_documents_user_created ON documents (user_id, created_at DESC);
CREATE INDEX idx_documents_user_status ON documents (user_id, status);
CREATE INDEX idx_documents_content_hash ON documents (user_id, content_hash);

-- 3. Conversations Table
CREATE TABLE conversations (
    id CHAR(36) NOT NULL,
    user_id CHAR(36) NOT NULL,
    title VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    CONSTRAINT pk_conversations PRIMARY KEY (id),
    CONSTRAINT fk_conversations_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

CREATE INDEX idx_conversations_user_updated ON conversations (user_id, updated_at DESC);

-- 4. Conversation Documents (Join Table with Explicit Unique Constraint)
CREATE TABLE conversation_documents (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_conversation_documents PRIMARY KEY (id),
    CONSTRAINT uk_cd_conversation_document UNIQUE (conversation_id, document_id),
    CONSTRAINT fk_cd_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE,
    CONSTRAINT fk_cd_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
);

CREATE INDEX idx_cd_document_id ON conversation_documents (document_id);

-- 5. Messages Table
CREATE TABLE messages (
    id CHAR(36) NOT NULL,
    conversation_id CHAR(36) NOT NULL,
    role VARCHAR(20) NOT NULL,
    content LONGTEXT NOT NULL,
    token_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_messages PRIMARY KEY (id),
    CONSTRAINT fk_messages_conversation FOREIGN KEY (conversation_id) REFERENCES conversations (id) ON DELETE CASCADE
);

CREATE INDEX idx_messages_conversation_created ON messages (conversation_id, created_at ASC);

-- 6. Message Sources (Citations / Grounding Excerpts)
CREATE TABLE message_sources (
    id CHAR(36) NOT NULL,
    message_id CHAR(36) NOT NULL,
    document_id CHAR(36) NOT NULL,
    page_number INT NOT NULL,
    chunk_index INT NOT NULL,
    relevance_score FLOAT NOT NULL,
    excerpt_text TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_message_sources PRIMARY KEY (id),
    CONSTRAINT fk_ms_message FOREIGN KEY (message_id) REFERENCES messages (id) ON DELETE CASCADE,
    CONSTRAINT fk_ms_document FOREIGN KEY (document_id) REFERENCES documents (id) ON DELETE CASCADE
);

CREATE INDEX idx_ms_message_id ON message_sources (message_id);
CREATE INDEX idx_ms_document_id ON message_sources (document_id);
