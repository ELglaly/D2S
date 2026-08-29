package com.schoolbridge.api.integrations;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.integrations.whatsapp.TemplateParam;
import java.util.List;

/**
 * Input to {@link NotificationDispatcher#dispatch(DispatchRequest)}. Carries both the WhatsApp
 * template shape and a pre-rendered SMS body so the fallback channel does not need access to the
 * i18n bundle. The caller (consumer/OTP service) is responsible for rendering both forms from the
 * same message key so the channels stay in sync â€” see open question (3) in {@code HANDOFF_M7.md}.
 */
public record DispatchRequest(
    String recipientPhone,
    Language language,
    String templateName,
    List<TemplateParam> templateParams,
    String smsBody) {}

