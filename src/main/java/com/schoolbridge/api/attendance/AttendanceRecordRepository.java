package com.schoolbridge.api.attendance;

import com.schoolbridge.api.attendance.dto.AttendanceRosterEntry;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

/**
 * Tenant-scoped repository for {@link AttendanceRecord}.
 *
 * <p>{@link #findById} is overridden with explicit JPQL â€” see {@code UserRepository} for rationale.
 */
public interface AttendanceRecordRepository extends JpaRepository<AttendanceRecord, UUID> {

  @Override
  @Query("select r from AttendanceRecord r where r.id = :id")
  Optional<AttendanceRecord> findById(@Param("id") UUID id);

  /** Idempotent-upsert lookup keyed on the unique constraint. */
  Optional<AttendanceRecord> findByStudentIdAndClassIdAndDate(
      UUID studentId, UUID classId, LocalDate date);

  /**
   * One row per enrolled student in the class, with their attendance row for the date when one
   * exists (nullable). Drives {@code GET /attendance/roster}. Ordered by encrypted full-name's
   * stored ciphertext â€” fine because all students in one school share the same key, so the order is
   * stable per school; clients should rely on the explicit display-name field, not the row order.
   */
  @Query(
      "select new com.schoolbridge.api.attendance.dto.AttendanceRosterEntry("
          + "s.id, s.fullName, r.id, r.status, r.markedAt) "
          + "from Enrollment e "
          + "join Student s on s.id = e.studentId "
          + "left join AttendanceRecord r on r.studentId = s.id "
          + "  and r.classId = :classId and r.date = :date "
          + "where e.classId = :classId")
  List<AttendanceRosterEntry> findRosterForClassAndDate(
      @Param("classId") UUID classId, @Param("date") LocalDate date);

  /** Range query for {@code GET /attendance/history}. */
  @Query(
      "select r from AttendanceRecord r "
          + "where r.studentId = :studentId and r.date >= :fromDate and r.date <= :toDate "
          + "order by r.date desc")
  List<AttendanceRecord> findStudentHistory(
      @Param("studentId") UUID studentId,
      @Param("fromDate") LocalDate fromDate,
      @Param("toDate") LocalDate toDate);

  /** All rows for one class on one date â€” used by {@code mark-all-present} to compute the delta. */
  @Query("select r from AttendanceRecord r where r.classId = :classId and r.date = :date")
  List<AttendanceRecord> findAllByClassIdAndDate(
      @Param("classId") UUID classId, @Param("date") LocalDate date);
}

