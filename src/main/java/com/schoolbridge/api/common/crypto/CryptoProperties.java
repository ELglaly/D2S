package com.schoolbridge.api.common.crypto;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Crypto material for column-level encryption. Both keys are 32-byte values, base64-encoded. Dev
 * defaults live in {@code application.yml}; production supplies them via env vars.
 */
@ConfigurationProperties("schoolbridge.crypto")
public record CryptoProperties(String aesKey, String blindIndexKey) {}
