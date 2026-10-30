package com.aidocassistant.dto;

import java.util.List;
import java.util.UUID;

/**
 * Response payload summarizing the outcome of a bulk document deletion request.
 */
public record BulkDeleteResponse(
        int deletedCount,
        List<UUID> deletedIds,
        List<UUID> failedIds,
        String message
) {}
