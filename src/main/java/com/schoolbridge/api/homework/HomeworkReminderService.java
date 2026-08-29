package com.schoolbridge.api.homework;

import java.util.UUID;

public interface HomeworkReminderService {

  /** Stamps {@code reminderSentAt} then fans out to all PENDING recipients for this item. */
  void dispatchReminder(UUID homeworkId);

  /** Releases a single DEFERRED recipient once the quiet window has closed. */
  void releaseDeferred(UUID recipientId);
}

