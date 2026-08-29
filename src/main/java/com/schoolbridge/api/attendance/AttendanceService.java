package com.schoolbridge.api.attendance;

import com.schoolbridge.api.attendance.dto.AttendanceHistoryEntry;
import com.schoolbridge.api.attendance.dto.AttendanceRecordResponse;
import com.schoolbridge.api.attendance.dto.AttendanceRosterEntry;
import com.schoolbridge.api.attendance.dto.MarkAllPresentRequest;
import com.schoolbridge.api.attendance.dto.MarkAllPresentResponse;
import com.schoolbridge.api.attendance.dto.MarkAttendanceRequest;
import com.schoolbridge.api.attendance.dto.ParentResponseRequest;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

public interface AttendanceService {

  AttendanceRecordResponse findById(UUID id);

  AttendanceRecordResponse mark(UUID markedByUserId, MarkAttendanceRequest request);

  MarkAllPresentResponse markAllPresent(UUID markedByUserId, MarkAllPresentRequest request);

  List<AttendanceRosterEntry> roster(UUID classId, LocalDate date);

  List<AttendanceHistoryEntry> history(UUID studentId, LocalDate fromDate, LocalDate toDate);

  AttendanceRecordResponse recordParentResponse(
      UUID recordId, UUID parentUserId, ParentResponseRequest request);
}

