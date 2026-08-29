package com.schoolbridge.api.integrations.push;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Typed binding for {@code schoolbridge.push.*}. */
@ConfigurationProperties(prefix = "schoolbridge.push")
public class PushProperties {

  private final Fcm fcm = new Fcm();

  public Fcm getFcm() {
    return fcm;
  }

  public static class Fcm {

    private boolean enabled = false;

    /** Path to a Google service-account JSON file. Falls back to GOOGLE_APPLICATION_CREDENTIALS. */
    private String credentialsFile;

    public boolean isEnabled() {
      return enabled;
    }

    public void setEnabled(boolean enabled) {
      this.enabled = enabled;
    }

    public String getCredentialsFile() {
      return credentialsFile;
    }

    public void setCredentialsFile(String credentialsFile) {
      this.credentialsFile = credentialsFile;
    }
  }
}

