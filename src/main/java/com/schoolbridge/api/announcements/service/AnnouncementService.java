package com.schoolbridge.api.announcements.service;

import com.schoolbridge.api.announcements.dto.AnnouncementRecipientResponse;
import com.schoolbridge.api.announcements.dto.AnnouncementResponse;
import com.schoolbridge.api.announcements.dto.CreateAnnouncementRequest;
import com.schoolbridge.api.announcements.enums.AnnouncementStatus;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

public interface AnnouncementService {

  AnnouncementResponse create(UUID schoolId, UUID senderId, CreateAnnouncementRequest request);

  Page<AnnouncementResponse> list(AnnouncementStatus status, Pageable pageable);

  AnnouncementResponse findById(UUID id);

  AnnouncementResponse recall(UUID id);

  Page<AnnouncementRecipientResponse> listRecipients(UUID id, Pageable pageable);

  void acknowledge(UUID announcementId, UUID parentUserId);
}

