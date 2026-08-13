package com.schoolbridge.api.attachments.dto;

import com.schoolbridge.api.attachments.Attachment;

/** Entity → response projection. */
public final class AttachmentMapper {

  private AttachmentMapper() {}

  public static AttachmentResponse toResponse(Attachment attachment) {
    return new AttachmentResponse(
        attachment.getId(),
        attachment.getFileName(),
        attachment.getContentType(),
        attachment.getSizeBytes(),
        attachment.getStatus(),
        attachment.getRejectionReason(),
        attachment.getAvResult(),
        attachment.getUploaderUserId(),
        attachment.getCreatedAt(),
        attachment.getCompletedAt());
  }
}
