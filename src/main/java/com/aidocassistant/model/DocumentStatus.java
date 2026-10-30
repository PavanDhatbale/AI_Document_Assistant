package com.aidocassistant.model;

/**
 * Processing status lifecycle for uploaded documents.
 */
public enum DocumentStatus {
    PENDING,
    PROCESSING,
    READY,
    FAILED,
    DELETING,
    DELETION_FAILED
}
