package com.schoolbridge.api.assistant.rag;

import com.schoolbridge.api.assistant.AssistantContextFactory;
import com.schoolbridge.api.assistant.rag.dto.IngestDocumentRequest;
import com.schoolbridge.api.assistant.rag.dto.KnowledgeDocumentResponse;
import com.schoolbridge.api.assistant.tools.ToolContext;
import com.schoolbridge.api.common.security.authz.Permission;
import com.schoolbridge.api.common.security.authz.RequirePermission;
import com.schoolbridge.api.common.web.ApiConstants;
import jakarta.validation.Valid;
import java.net.URI;
import java.util.List;
import java.util.UUID;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SCHOOL_ADMIN-only management of the tenant's RAG knowledge corpus (loaded only when {@code
 * schoolbridge.assistant.enabled=true}). Ingestion, listing, and deletion are always scoped to the
 * caller's tenant by {@link DocumentIngestionService}; retrieval into the chat flow is wired
 * separately in Phase 3.
 */
@RestController
@RequestMapping(ApiConstants.API_V1 + "/assistant/knowledge")
@ConditionalOnProperty(prefix = "schoolbridge.assistant", name = "enabled", havingValue = "true")
public class DocumentAdminController {

  private final DocumentIngestionService ingestion;
  private final AssistantContextFactory contextFactory;

  public DocumentAdminController(
      DocumentIngestionService ingestion, AssistantContextFactory contextFactory) {
    this.ingestion = ingestion;
    this.contextFactory = contextFactory;
  }

  @PostMapping
  @RequirePermission(Permission.DOCUMENT_MANAGE)
  public ResponseEntity<KnowledgeDocumentResponse> ingest(
      @Valid @RequestBody IngestDocumentRequest request, Authentication authentication) {
    ToolContext ctx = contextFactory.fromAuthentication(authentication, locale());
    KnowledgeDocumentResponse created = ingestion.ingest(request, ctx);
    return ResponseEntity.created(
            URI.create(ApiConstants.API_V1 + "/assistant/knowledge/" + created.id()))
        .body(created);
  }

  @GetMapping
  @RequirePermission(Permission.DOCUMENT_MANAGE)
  public List<KnowledgeDocumentResponse> list(Authentication authentication) {
    ToolContext ctx = contextFactory.fromAuthentication(authentication, locale());
    return ingestion.list(ctx);
  }

  @DeleteMapping("/{id}")
  @RequirePermission(Permission.DOCUMENT_MANAGE)
  public ResponseEntity<Void> delete(@PathVariable UUID id, Authentication authentication) {
    ToolContext ctx = contextFactory.fromAuthentication(authentication, locale());
    ingestion.delete(id, ctx);
    return ResponseEntity.noContent().build();
  }

  private static java.util.Locale locale() {
    return LocaleContextHolder.getLocale();
  }
}
