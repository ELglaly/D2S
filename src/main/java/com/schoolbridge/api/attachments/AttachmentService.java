package com.schoolbridge.api.attachments;

import com.schoolbridge.api.attachments.dto.AttachmentDownloadTicket;
import com.schoolbridge.api.attachments.dto.AttachmentResponse;
import com.schoolbridge.api.attachments.dto.AttachmentUploadTicket;
import com.schoolbridge.api.attachments.dto.CreateAttachmentRequest;
import java.util.UUID;

/** The attachment pipeline: presign in, inspect, presign out. */
public interface AttachmentService {

  /**
   * Records a pending attachment and returns a presigned PUT the client uses to upload the bytes
   * directly to object storage.
   */
  AttachmentUploadTicket createUpload(
      UUID schoolId, UUID uploaderUserId, CreateAttachmentRequest request);

  /**
   * Called by the client once its PUT succeeded. Verifies the object exists at the expected size,
   * sniffs its real type, scans it, and moves it to a terminal state. Objects that fail inspection
   * are deleted from storage here rather than left for the sweeper â€” an unsafe object should not
   * outlive the request that identified it.
   */
  AttachmentResponse complete(UUID attachmentId);

  /** Metadata only. */
  AttachmentResponse get(UUID attachmentId);

  /** A short-lived presigned GET. Issued only for {@link AttachmentStatus#CLEAN}. */
  AttachmentDownloadTicket download(UUID attachmentId);

  /** Deletes object and row. Refuses while a homework item or announcement still references it. */
  void delete(UUID attachmentId, UUID actorUserId);

  /**
   * Resolves a client-supplied attachment reference for another module, asserting it exists in this
   * school and is {@code CLEAN}. Throws rather than returning empty, so a caller cannot forget to
   * check.
   */
  void requireUsableReference(UUID schoolId, String attachmentReference);
}

