package com.schoolbridge.api.identity.otp;

/**
 * Port for delivering a one-time PIN to a parent. The WhatsApp Cloud API implementation lands in
 * the integrations module (M7); until then {@link LoggingOtpDispatcher} just prints the code so the
 * flow can be exercised end-to-end in dev.
 */
public interface OtpDispatcher {

  void dispatch(String phone, String code);
}

