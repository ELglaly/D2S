package com.schoolbridge.api.common.security;

import com.schoolbridge.api.identity.jwt.JwtService;
import com.schoolbridge.api.identity.otp.OtpService;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

/**
 * Stateless security chain. Public endpoints are explicitly allow-listed; every other request must
 * present a Bearer credential (JWT for staff, opaque token for parents) resolved by {@link
 * BearerAuthenticationFilter}, after which {@link TenantBindingFilter} binds the current school for
 * the request.
 *
 * <p>Swagger UI and API-docs paths are conditionally included in the public allow-list based on
 * {@code springdoc.api-docs.enabled} — set to {@code false} in prod to remove docs exposure.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {

  private static final String[] ALWAYS_PUBLIC_PATHS = {
    "/actuator/health",
    "/actuator/health/**",
    "/actuator/info",
    "/actuator/prometheus",
    "/api/v1/auth/**",
    "/api/v1/parents/auth/**",
    "/integrations/whatsapp/webhook"
  };

  private static final String[] SWAGGER_PATHS = {
    "/v3/api-docs", "/v3/api-docs.yaml", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html"
  };

  private final RestAuthenticationEntryPoint authenticationEntryPoint;
  private final JwtService jwtService;
  private final OtpService otpService;

  @Value("${springdoc.api-docs.enabled:true}")
  private boolean swaggerEnabled;

  @Value(
      "${schoolbridge.cors.allowed-origins:http://localhost:5173,http://localhost:8080,http://10.0.2.2:8080,http://localhost:63342}")
  private List<String> allowedOrigins;

  public SecurityConfig(
      RestAuthenticationEntryPoint authenticationEntryPoint,
      JwtService jwtService,
      OtpService otpService) {
    this.authenticationEntryPoint = authenticationEntryPoint;
    this.jwtService = jwtService;
    this.otpService = otpService;
  }

  @Bean
  public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
    BearerAuthenticationFilter bearer =
        new BearerAuthenticationFilter(jwtService, otpService, authenticationEntryPoint);
    TenantBindingFilter tenantBinding = new TenantBindingFilter();

    String[] publicPaths =
        swaggerEnabled
            ? Stream.concat(Arrays.stream(ALWAYS_PUBLIC_PATHS), Arrays.stream(SWAGGER_PATHS))
                .toArray(String[]::new)
            : ALWAYS_PUBLIC_PATHS;

    http.csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigurationSource()))
        .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
        .authorizeHttpRequests(
            auth -> auth.requestMatchers(publicPaths).permitAll().anyRequest().authenticated())
        .httpBasic(b -> b.disable())
        .formLogin(f -> f.disable())
        .exceptionHandling(e -> e.authenticationEntryPoint(authenticationEntryPoint))
        .addFilterBefore(bearer, AuthorizationFilter.class)
        .addFilterAfter(tenantBinding, BearerAuthenticationFilter.class);
    return http.build();
  }

  /** bcrypt cost 12 (NFR-S1). Argon2id can be swapped in once BouncyCastle is on the classpath. */
  @Bean
  public PasswordEncoder passwordEncoder() {
    return new BCryptPasswordEncoder(12);
  }

  @Bean
  public CorsConfigurationSource corsConfigurationSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOrigins(allowedOrigins);
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setExposedHeaders(List.of("X-Request-Id"));
    config.setAllowCredentials(true);
    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);
    return source;
  }
}
