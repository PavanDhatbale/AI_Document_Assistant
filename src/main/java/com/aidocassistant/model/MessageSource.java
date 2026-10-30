package com.aidocassistant.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

/**
 * MessageSource entity capturing grounding citations for assistant responses.
 */
@Entity
@Table(name = "message_sources")
public class MessageSource {

    @Id
    @JdbcTypeCode(SqlTypes.CHAR)
    @Column(name = "id", length = 36, nullable = false, updatable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "message_id", nullable = false)
    private Message message;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "document_id", nullable = false)
    private Document document;

    @Column(name = "page_number", nullable = false)
    private int pageNumber;

    @Column(name = "chunk_index", nullable = false)
    private int chunkIndex;

    @Column(name = "relevance_score", nullable = false)
    private float relevanceScore;

    @Column(name = "excerpt_text", columnDefinition = "TEXT", nullable = false)
    private String excerptText;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    public MessageSource() {
    }

    public MessageSource(UUID id, Message message, Document document, int pageNumber, int chunkIndex, float relevanceScore, String excerptText) {
        this.id = id != null ? id : UUID.randomUUID();
        this.message = message;
        this.document = document;
        this.pageNumber = pageNumber;
        this.chunkIndex = chunkIndex;
        this.relevanceScore = relevanceScore;
        this.excerptText = excerptText;
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public Message getMessage() {
        return message;
    }

    public void setMessage(Message message) {
        this.message = message;
    }

    public Document getDocument() {
        return document;
    }

    public void setDocument(Document document) {
        this.document = document;
    }

    public int getPageNumber() {
        return pageNumber;
    }

    public void setPageNumber(int pageNumber) {
        this.pageNumber = pageNumber;
    }

    public int getChunkIndex() {
        return chunkIndex;
    }

    public void setChunkIndex(int chunkIndex) {
        this.chunkIndex = chunkIndex;
    }

    public float getRelevanceScore() {
        return relevanceScore;
    }

    public void setRelevanceScore(float relevanceScore) {
        this.relevanceScore = relevanceScore;
    }

    public String getExcerptText() {
        return excerptText;
    }

    public void setExcerptText(String excerptText) {
        this.excerptText = excerptText;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MessageSource other)) return false;
        return id != null && id.equals(other.getId());
    }

    @Override
    public int hashCode() {
        return getClass().hashCode();
    }

    @Override
    public String toString() {
        return "MessageSource{" +
                "id=" + id +
                ", pageNumber=" + pageNumber +
                ", chunkIndex=" + chunkIndex +
                ", relevanceScore=" + relevanceScore +
                ", createdAt=" + createdAt +
                '}';
    }
}
