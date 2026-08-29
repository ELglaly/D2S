package com.schoolbridge.api.integrations.sms;

import com.schoolbridge.api.announcements.enums.Language;
import com.schoolbridge.api.integrations.whatsapp.MessageSendResult;

/**
 * Hex-boundary port for the SMS fallback channel. M7 ships a fake-only implementation; a real
 * provider (Vonage / Twilio / a local Egyptian aggregator) lands in M14 hardening per the M7
 * handoff. The interface lives on the {@code integrations.sms} package so the M14 work does not
 * need to widen it.
 */
public interface SmsClient {

  MessageSendResult send(String recipientPhone, String body, Language language);
}

