package com.schoolbridge.api.identity;

/** Discriminates a refresh-token subject between the two account tables. */
public enum SubjectKind {
  USER,
  PLATFORM_ADMIN
}
