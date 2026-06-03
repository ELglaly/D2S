package com.schoolbridge.api;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/** Entry point for the SchoolBridge multi-tenant backend. */
@SpringBootApplication
public class SchoolBridgeApplication {

  public static void main(String[] args) {
    SpringApplication.run(SchoolBridgeApplication.class, args);
  }
}
