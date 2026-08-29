package com.schoolbridge.api.identity.jwt;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * JWT signing material and token lifetimes. PEM-encoded keys come from env vars in prod; left
 * blank, {@link JwtService} generates an ephemeral keypair at startup (dev/test only).
 */
@ConfigurationProperties("schoolbridge.jwt")
public record JwtProperties(
    String privateKey,
    String publicKey,
    Duration accessTtl,
    Duration refreshTtl,
    String issuer,
    String kid) {}

