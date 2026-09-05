package com.schoolbridge.api.classes.dto;

import java.util.List;

/**
 * Summary returned by {@code POST /students/bulk-import}. Each rejected row carries its 1-based row
 * number (including the header) and a human-readable reason so the operator can fix and re-upload.
 */
public record BulkImportResult(
    int importedCount, int rejectedCount, List<RejectedRow> rejectedRows) {

  public record RejectedRow(int rowNumber, String reason) {}
}
