package com.schoolbridge.api.assistant.rag;

/**
 * Kind of knowledge source ingested for assistant RAG. Stored as a string on assistant_documents.
 */
public enum DocType {
  KB,
  FAQ,
  TOOL_DOC,
  GUIDE,
  BUSINESS_RULE,
  UPLOAD
}

