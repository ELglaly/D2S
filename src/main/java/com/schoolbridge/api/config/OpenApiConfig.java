package com.schoolbridge.api.config;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeIn;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Contact;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.security.SecuritySchemes;
import io.swagger.v3.oas.annotations.servers.Server;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.media.IntegerSchema;
import io.swagger.v3.oas.models.media.Schema;
import io.swagger.v3.oas.models.media.StringSchema;
import org.springdoc.core.customizers.OpenApiCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Global OpenAPI definition for the SchoolBridge API.
 *
 * <p>Response envelope contract (applies to all 2xx non-204 endpoints):
 *
 * <pre>
 * Single resource:  { "data": &lt;T&gt;,    "meta": null }
 * Paginated list:   { "data": &lt;T[]&gt;,  "meta": { "page", "size", "totalElements", "totalPages" } }
 * Error (4xx/5xx): RFC 7807 ProblemDetail â€” { "type", "title", "detail", "status", "traceId" }
 * Empty (204):     no body
 * </pre>
 *
 * <p>Authentication: send the token in the {@code Authorization: Bearer <token>} header. Staff and
 * platform-admins use the JWT issued by {@code POST /api/v1/auth/login}. Parents use the opaque
 * token issued by {@code POST /api/v1/parents/auth/verify-otp}. Tenancy is embedded in the token â€”
 * no tenant header is required or accepted.
 */
@Configuration
@OpenAPIDefinition(
    info =
        @Info(
            title = "SchoolBridge API",
            version = "v1",
            description =
                "WhatsApp-first school-parent communication platform. "
                    + "All 2xx responses (except 204) are wrapped: "
                    + "{ \"data\": T, \"meta\": PageMeta | null }. "
                    + "Errors use RFC 7807 ProblemDetail. "
                    + "Tenancy is carried by the bearer token â€” no tenant header is required.",
            contact = @Contact(name = "SchoolBridge Engineering")),
    servers = {@Server(url = "/", description = "Default")},
    security = {@SecurityRequirement(name = "BearerJWT")})
@SecuritySchemes({
  @SecurityScheme(
      name = "BearerJWT",
      type = SecuritySchemeType.HTTP,
      scheme = "bearer",
      bearerFormat = "JWT",
      in = SecuritySchemeIn.HEADER,
      description = "RS256 JWT issued by POST /api/v1/auth/login (staff/admin)."),
  @SecurityScheme(
      name = "ParentToken",
      type = SecuritySchemeType.HTTP,
      scheme = "bearer",
      in = SecuritySchemeIn.HEADER,
      description =
          "Opaque 24-hour sliding-TTL token issued by POST /api/v1/parents/auth/verify-otp.")
})
public class OpenApiConfig {

  @Bean
  public OpenApiCustomizer problemDetailSchemaCustomizer() {
    Schema<?> problemDetail =
        new Schema<>()
            .type("object")
            .description("RFC 7807 ProblemDetail â€” returned for all 4xx/5xx responses")
            .addProperty("type", new StringSchema().example("about:blank"))
            .addProperty("title", new StringSchema().example("Unprocessable Entity"))
            .addProperty("status", new IntegerSchema().example(422))
            .addProperty("detail", new StringSchema().example("Validation failed on field 'body'"))
            .addProperty("instance", new StringSchema().example("/api/v1/announcements"))
            .addProperty("traceId", new StringSchema().example("65f1a2b3c4d5e6f7"));

    return openApi -> {
      if (openApi.getComponents() == null) {
        openApi.setComponents(new Components());
      }
      openApi.getComponents().addSchemas("ProblemDetail", problemDetail);
    };
  }
}

