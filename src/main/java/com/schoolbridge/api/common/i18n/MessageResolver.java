package com.schoolbridge.api.common.i18n;

import java.util.Locale;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.stereotype.Component;

/**
 * Resolves i18n message keys against the current request locale (driven by {@code
 * Accept-Language}). Missing keys fall back to the key itself rather than throwing.
 */
@Component
public class MessageResolver {

  private final MessageSource messageSource;

  public MessageResolver(MessageSource messageSource) {
    this.messageSource = messageSource;
  }

  public String get(String key, Object... args) {
    return messageSource.getMessage(key, args, key, LocaleContextHolder.getLocale());
  }

  /** Resolve a key in an explicit locale (used to render both ar + en for action previews). */
  public String get(Locale locale, String key, Object... args) {
    return messageSource.getMessage(key, args, key, locale);
  }
}
