package com.schoolbridge.api;

import com.schoolbridge.api.assistant.llm.AssistantProperties;
import com.schoolbridge.api.integrations.whatsapp.WhatsAppProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** Entry point for the SchoolBridge multi-tenant backend. */
@SpringBootApplication
@EnableConfigurationProperties({WhatsAppProperties.class, AssistantProperties.class})
public class SchoolBridgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(SchoolBridgeApplication.class, args);
  }
}

