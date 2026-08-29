package com.schoolbridge.api.attachments;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.UUID;

/**
 * Builds the object-storage key for an attachment. One place decides what a key looks like, and
 * that place takes a school id and an attachment id â€” never anything the client supplied.
 *
 * <pre>{@code {schoolId}/{yyyy}/{MM}/{attachmentId}}</pre>
 *
 * <p>A client-influenced key would be a cross-tenant write primitive: a caller who can choose the
 * key can choose another school's prefix, and the presigned PUT would sign it. It would also make
 * traversal-style keys ({@code ../}) worth trying against backends that normalise paths.
 *
 * <p>The school prefix is not only for isolation â€” it makes bucket policy, lifecycle rules, and
 * per-tenant export or erasure expressible without reading the database.
 */
public final class AttachmentKeys {

  private static final DateTimeFormatter YEAR_MONTH =
      DateTimeFormatter.ofPattern("yyyy/MM").withZone(ZoneOffset.UTC);

  private AttachmentKeys() {}

  /**
   * @param objectId server-generated, unguessable, and never derived from client input. It is not
   *     the attachment row id: Hibernate assigns that at persist, and the key column is immutable,
   *     so the key has to exist before the row does.
   */
  public static String forAttachment(UUID schoolId, UUID objectId, Instant when) {
    return schoolId + "/" + YEAR_MONTH.format(when) + "/" + objectId;
  }

  /**
   * True when {@code key} sits under {@code schoolId}'s prefix. Defence in depth on the read path.
   */
  public static boolean belongsToSchool(String key, UUID schoolId) {
    return key != null && key.startsWith(schoolId + "/");
  }
}

