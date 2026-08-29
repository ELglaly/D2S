package com.schoolbridge.api.identity.otp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Dev-time {@link OtpDispatcher} that just logs the code. Constrained to {@code local} and {@code
 * test} profiles since M7 introduced {@link
 * com.schoolbridge.api.integrations.whatsapp.WhatsAppOtpDispatcher} as the production dispatcher.
 * {@code @ConditionalOnMissingBean} still leaves room for tests to override with a capturing fake.
 * Logs at WARN so it stands out and is impossible to miss if it accidentally runs in a higher
 * environment.
 */
@Component
@Profile({"local", "test"})
@ConditionalOnMissingBean(value = OtpDispatcher.class, ignored = LoggingOtpDispatcher.class)
public class LoggingOtpDispatcher implements OtpDispatcher {

  private static final Logger log = LoggerFactory.getLogger(LoggingOtpDispatcher.class);

  @Override
  public void dispatch(String phone, String code) {
    log.warn("otp_dispatch_dev_only phone={} code={}", maskPhone(phone), code);
  }

  private static String maskPhone(String phone) {
    if (phone == null || phone.length() <= 4) {
      return "***";
    }
    return "***" + phone.substring(phone.length() - 4);
  }
}

