package com.schoolbridge.api.integrations.whatsapp;

/**
 * A single positional parameter passed to a Meta-approved WhatsApp template. The parameter type is
 * always {@code text} for our two M7 templates ({@code parent_otp_v1}, {@code
 * school_announcement_v1}); media/document parameters can be added later when richer templates are
 * approved.
 */
public record TemplateParam(String text) {

  public static TemplateParam of(String value) {
    return new TemplateParam(value);
  }
}

