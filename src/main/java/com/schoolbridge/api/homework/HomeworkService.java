package com.schoolbridge.api.homework;

import com.schoolbridge.api.homework.dto.CreateHomeworkRequest;
import com.schoolbridge.api.homework.dto.HomeworkRecipientResponse;
import com.schoolbridge.api.homework.dto.HomeworkResponse;
import com.schoolbridge.api.homework.dto.ParentHomeworkFeedEntry;
import com.schoolbridge.api.homework.dto.UpdateHomeworkRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface HomeworkService {

  HomeworkResponse create(UUID schoolId, UUID actorId, CreateHomeworkRequest request);

  HomeworkResponse publish(UUID id);

  HomeworkResponse findById(UUID id);

  Page<HomeworkResponse> list(
      UUID classId,
      HomeworkStatus status,
      LocalDate dueDateFrom,
      LocalDate dueDateTo,
      Pageable pageable);

  HomeworkResponse update(UUID id, UpdateHomeworkRequest request);

  void archive(UUID id);

  Page<ParentHomeworkFeedEntry> parentFeed(UUID parentUserId, UUID childId, Pageable pageable);

  void acknowledge(UUID homeworkId, UUID parentUserId);

  /** Returns the item only if {@code parentUserId} has a recipient row for it; 404 otherwise. */
  HomeworkResponse findByIdForParent(UUID id, UUID parentUserId);

  /** Returns all recipient rows for a homework item with delivery and acknowledgment status. */
  List<HomeworkRecipientResponse> listRecipients(UUID homeworkId);
}

